package com.warsim.frontline.squad;

import com.warsim.frontline.api.match.MatchState;
import com.warsim.frontline.api.roster.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class DefaultRosterService implements RosterService {
    private final RosterConfiguration configuration;
    private final Map<UUID, TeamAssignment> assignments = new LinkedHashMap<>();
    private final Map<UUID, Reservation> reservations = new LinkedHashMap<>();
    private final Map<UUID, Instant> switchCooldowns = new LinkedHashMap<>();
    private UUID matchId;
    private Instant createdAt;
    private RosterState state;
    private long revision;
    private TeamSide tieBreakCursor = TeamSide.ATTACKERS;
    private String lastError;
    private long automaticAssignments;
    private long reconnectRestores;
    private long assignmentFailures;
    private long capacityRejections;
    private long squadSwitches;
    private long squadSwitchFailures;
    private long administratorMoves;
    private long administratorRebalances;
    private long leaderTransfers;
    private long leaderAutomaticTransfers;
    private long staleReservationRemovals;
    private long rollbacks;
    private long invariantViolations;

    public DefaultRosterService(UUID matchId, RosterConfiguration configuration, Instant createdAt) {
        this.matchId = Objects.requireNonNull(matchId, "matchId");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.state = configuration.teamsEnabled() ? RosterState.ACTIVE : RosterState.DISABLED;
    }

    @Override
    public synchronized RosterAdmissionPreparation prepareAdmission(
        UUID playerUuid, String currentName, Instant now, MatchState matchState
    ) {
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(now, "now");
        RosterOperationResult unavailable = writable();
        if (unavailable != null) {
            return RosterAdmissionPreparation.rejected(
                unavailable.failure(), unavailable.message()
            );
        }
        if (!acceptsAdmission(matchState)) {
            return preparationFailure(
                RosterFailure.NOT_MODIFIABLE, "当前对局状态不接受阵营分配", false
            );
        }
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing != null) {
            return RosterAdmissionPreparation.success(new RosterAdmissionPlan(
                playerUuid, existing.currentName(), matchId, revision, existing.teamSide(),
                existing.squadId(), now, existing.source(),
                existing.restoredAfterReconnect(), true
            ));
        }
        if (currentName == null || !currentName.matches("[A-Za-z0-9_]{1,16}")) {
            return preparationFailure(RosterFailure.INTERNAL_ERROR, "玩家名称非法", false);
        }

        Reservation reservation = reservations.get(playerUuid);
        if (reservation != null && !expired(reservation, now)) {
            SquadId squad = chooseRestoredSquad(reservation.assignment(), reservation.assignment().teamSide());
            if (configuration.squadsEnabled() && squad == null) {
                return preparationFailure(
                    RosterFailure.SQUADS_FULL, "原阵营全部小队已满", true
                );
            }
            return RosterAdmissionPreparation.success(new RosterAdmissionPlan(
                playerUuid, currentName, matchId, revision,
                reservation.assignment().teamSide(), Optional.ofNullable(squad), now,
                AssignmentSource.RECONNECT_RESTORE, true, false
            ));
        }

        TeamSide side = chooseTeam();
        if (side == null) {
            return preparationFailure(RosterFailure.TEAMS_FULL, "双方阵营均已满员", true);
        }
        SquadId squad = configuration.squadsEnabled() && configuration.autoAssignSquads()
            ? chooseSquad(side, null) : null;
        if (configuration.squadsEnabled() && configuration.autoAssignSquads() && squad == null) {
            return preparationFailure(
                RosterFailure.SQUADS_FULL, "目标阵营全部小队已满", true
            );
        }
        return RosterAdmissionPreparation.success(new RosterAdmissionPlan(
            playerUuid, currentName, matchId, revision, side, Optional.ofNullable(squad),
            now, AssignmentSource.AUTOMATIC, false, false
        ));
    }

    @Override
    public synchronized RosterOperationResult commitAdmission(RosterAdmissionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!matchId.equals(plan.matchId()) || revision != plan.expectedRevision()) {
            return fail(RosterFailure.STALE_PLAN, "接纳计划已过期");
        }
        TeamAssignment existing = assignments.get(plan.playerUuid());
        if (plan.idempotent()) {
            return existing == null
                ? fail(RosterFailure.STALE_PLAN, "幂等接纳计划已失效")
                : RosterOperationResult.success("已存在有效分配", existing);
        }
        TeamAssignment created = assignment(
            plan.playerUuid(), plan.currentName(), plan.teamSide(), plan.squadId().orElse(null),
            plan.plannedAt(), plan.source(), plan.restoredAfterReconnect()
        );
        if (plan.source() == AssignmentSource.RECONNECT_RESTORE) {
            reservations.remove(plan.playerUuid());
        } else {
            Reservation expired = reservations.get(plan.playerUuid());
            if (expired != null && expired(expired, plan.plannedAt())) {
                reservations.remove(plan.playerUuid());
            }
        }
        assignments.put(plan.playerUuid(), created);
        normalizeLeaders();
        if (plan.source() == AssignmentSource.AUTOMATIC) {
            if (teamLoad(TeamSide.ATTACKERS) == teamLoad(TeamSide.DEFENDERS)) {
                tieBreakCursor = tieBreakCursor.opposite();
            } else if (teamLoad(plan.teamSide()) - 1 == teamLoad(plan.teamSide().opposite())) {
                tieBreakCursor = plan.teamSide().opposite();
            }
        }
        revision++;
        if (plan.source() == AssignmentSource.RECONNECT_RESTORE) reconnectRestores++;
        else automaticAssignments++;
        return RosterOperationResult.success(
            plan.restoredAfterReconnect() ? "已恢复原阵营与小队" : "阵营与小队分配成功",
            assignments.get(plan.playerUuid())
        );
    }

    @Override
    public synchronized RosterOperationResult admit(
        UUID playerUuid, String currentName, Instant now, MatchState matchState
    ) {
        RosterAdmissionPreparation preparation =
            prepareAdmission(playerUuid, currentName, now, matchState);
        return preparation.successful()
            ? commitAdmission(preparation.plan())
            : RosterOperationResult.rejected(preparation.failure(), preparation.message());
    }

    @Override
    public synchronized RosterOperationResult disconnect(UUID playerUuid, Instant now) {
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing == null) return fail(RosterFailure.PLAYER_NOT_ASSIGNED, "玩家没有有效分配");
        UUID oldLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        assignments.remove(playerUuid);
        TeamAssignment disconnected = new TeamAssignment(
            existing.playerUuid(), existing.currentName(), existing.matchId(),
            existing.teamSide(), existing.squadId(), existing.squadRole(),
            existing.assignedAt(), existing.source(), existing.restoredAfterReconnect(), false
        );
        if (configuration.reconnectRestoreAssignment()) {
            reservations.put(playerUuid, new Reservation(disconnected, now));
        }
        switchCooldowns.remove(playerUuid);
        normalizeLeaders();
        UUID newLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        if (oldLeader != null && !Objects.equals(oldLeader, newLeader) && newLeader != null) {
            leaderAutomaticTransfers++;
        }
        revision++;
        return RosterOperationResult.success("已保留同局重连阵营位置", disconnected);
    }

    @Override
    public synchronized boolean rollbackAdmission(UUID playerUuid) {
        TeamAssignment removed = assignments.remove(playerUuid);
        if (removed == null) return false;
        normalizeLeaders();
        switchCooldowns.remove(playerUuid);
        revision++;
        rollbacks++;
        return true;
    }

    @Override
    public synchronized int cleanupExpired(Instant now) {
        List<UUID> expired = reservations.entrySet().stream()
            .filter(entry -> expired(entry.getValue(), now))
            .map(Map.Entry::getKey).toList();
        expired.forEach(reservations::remove);
        if (!expired.isEmpty()) {
            revision++;
            staleReservationRemovals += expired.size();
        }
        return expired.size();
    }

    @Override
    public synchronized RosterOperationResult switchSquad(
        UUID playerUuid, SquadId target, MatchState matchState, Instant now, boolean administrator
    ) {
        RosterOperationResult unavailable = writable();
        if (unavailable != null) return unavailable;
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing == null) return switchFail(RosterFailure.PLAYER_NOT_ASSIGNED, "玩家没有有效分配");
        if (!configuration.squadsEnabled()) return switchFail(RosterFailure.DISABLED, "小队系统未启用");
        if (!administrator && !switchAllowed(matchState)) {
            return switchFail(RosterFailure.SWITCH_NOT_ALLOWED, "当前对局状态不允许切换小队");
        }
        if (existing.squadId().orElse(null) == target) {
            return RosterOperationResult.success("已在该小队", existing);
        }
        Instant cooldownUntil = switchCooldowns.get(playerUuid);
        if (!administrator && cooldownUntil != null && now.isBefore(cooldownUntil)) {
            return switchFail(RosterFailure.SWITCH_COOLDOWN, "小队切换仍在冷却中");
        }
        if (squadMembers(existing.teamSide(), target).size() >= configuration.maximumMembersPerSquad()) {
            return switchFail(RosterFailure.SQUAD_FULL, "目标小队已满");
        }
        UUID oldLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        TeamAssignment moved = new TeamAssignment(
            existing.playerUuid(), existing.currentName(), matchId, existing.teamSide(),
            Optional.of(target), SquadRole.MEMBER, now,
            administrator ? AssignmentSource.ADMINISTRATOR : existing.source(),
            existing.restoredAfterReconnect(), true
        );
        assignments.put(playerUuid, moved);
        normalizeLeaders();
        UUID newLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        if (oldLeader != null && !Objects.equals(oldLeader, newLeader) && newLeader != null) {
            leaderAutomaticTransfers++;
        }
        if (!administrator) {
            switchCooldowns.put(playerUuid, now.plusSeconds(configuration.switchCooldownSeconds()));
        }
        revision++;
        squadSwitches++;
        return RosterOperationResult.success("已切换小队", assignments.get(playerUuid));
    }

    @Override
    public synchronized RosterOperationResult leaveSquad(UUID playerUuid, Instant now) {
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing == null) return fail(RosterFailure.PLAYER_NOT_ASSIGNED, "玩家没有有效分配");
        if (existing.squadId().isEmpty()) return RosterOperationResult.success("当前未加入小队", existing);
        UUID oldLeader = leader(existing.teamSide(), existing.squadId().orElseThrow());
        assignments.put(playerUuid, new TeamAssignment(
            playerUuid, existing.currentName(), matchId, existing.teamSide(), Optional.empty(),
            SquadRole.MEMBER, existing.assignedAt(), existing.source(),
            existing.restoredAfterReconnect(), true
        ));
        normalizeLeaders();
        UUID newLeader = leader(existing.teamSide(), existing.squadId().orElseThrow());
        if (oldLeader != null && !Objects.equals(oldLeader, newLeader) && newLeader != null) {
            leaderAutomaticTransfers++;
        }
        revision++;
        return RosterOperationResult.success("已离开小队，阵营保持不变", assignments.get(playerUuid));
    }

    @Override
    public synchronized RosterOperationResult transferLeader(
        UUID actorUuid, UUID targetUuid, boolean administrator, Instant now
    ) {
        TeamAssignment target = assignments.get(targetUuid);
        if (target == null || target.squadId().isEmpty()) {
            return fail(RosterFailure.TARGET_NOT_IN_SQUAD, "目标玩家不在有效小队");
        }
        UUID currentLeader = leader(target.teamSide(), target.squadId().orElseThrow());
        if (!administrator && !Objects.equals(currentLeader, actorUuid)) {
            return fail(RosterFailure.NOT_SQUAD_LEADER, "只有当前队长可以转移队长");
        }
        TeamAssignment actor = assignments.get(actorUuid);
        if (!administrator && (actor == null || actor.squadId().isEmpty()
            || actor.teamSide() != target.teamSide()
            || actor.squadId().orElseThrow() != target.squadId().orElseThrow())) {
            return fail(RosterFailure.TARGET_NOT_IN_SQUAD, "目标必须与队长处于同一小队");
        }
        if (Objects.equals(currentLeader, targetUuid)) {
            return RosterOperationResult.success("目标已经是队长", target);
        }
        setRole(currentLeader, SquadRole.MEMBER);
        setRole(targetUuid, SquadRole.LEADER);
        revision++;
        leaderTransfers++;
        return RosterOperationResult.success("队长已转移", assignments.get(targetUuid));
    }

    @Override
    public synchronized RosterOperationResult moveTeam(
        UUID playerUuid, TeamSide target, boolean force, MatchState matchState, Instant now
    ) {
        RosterOperationResult unavailable = writable();
        if (unavailable != null) return unavailable;
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing == null) return fail(RosterFailure.PLAYER_NOT_ASSIGNED, "玩家没有有效分配");
        if (existing.teamSide() == target) return RosterOperationResult.success("玩家已在目标阵营", existing);
        if (matchState == MatchState.PLAYING && !force) {
            return fail(RosterFailure.FORCE_REQUIRED, "进行中的对局必须使用 force");
        }
        if (matchState != MatchState.WAITING && matchState != MatchState.WARMUP
            && matchState != MatchState.PLAYING) {
            return fail(RosterFailure.NOT_MODIFIABLE, "当前对局状态不允许调整阵营");
        }
        if (teamLoad(target) >= configuration.maximumPlayers(target)) {
            return failCapacity(RosterFailure.TEAM_FULL, "目标阵营已满");
        }
        int targetAfter = activeCount(target) + 1;
        int sourceAfter = activeCount(existing.teamSide()) - 1;
        if (!force && Math.abs(targetAfter - sourceAfter) > configuration.maximumDifference()) {
            return fail(RosterFailure.BALANCE_LIMIT, "移动后阵营差值超过限制");
        }
        SquadId squad = configuration.squadsEnabled() ? chooseSquad(target, null) : null;
        if (configuration.squadsEnabled() && squad == null) {
            return failCapacity(RosterFailure.SQUADS_FULL, "目标阵营全部小队已满");
        }
        UUID oldLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        assignments.put(playerUuid, assignment(
            playerUuid, existing.currentName(), target, squad, now,
            AssignmentSource.ADMINISTRATOR, existing.restoredAfterReconnect()
        ));
        normalizeLeaders();
        UUID newLeader = leader(existing.teamSide(), existing.squadId().orElse(null));
        if (oldLeader != null && !Objects.equals(oldLeader, newLeader) && newLeader != null) {
            leaderAutomaticTransfers++;
        }
        revision++;
        administratorMoves++;
        return RosterOperationResult.success("管理员阵营调整成功", assignments.get(playerUuid));
    }

    @Override
    public synchronized RosterOperationResult rebalance(
        UUID playerUuid, MatchState matchState, Instant now
    ) {
        TeamAssignment existing = assignments.get(playerUuid);
        if (existing == null) return fail(RosterFailure.PLAYER_NOT_ASSIGNED, "玩家没有有效分配");
        int attackersWithout = activeCount(TeamSide.ATTACKERS)
            - (existing.teamSide() == TeamSide.ATTACKERS ? 1 : 0);
        int defendersWithout = activeCount(TeamSide.DEFENDERS)
            - (existing.teamSide() == TeamSide.DEFENDERS ? 1 : 0);
        TeamSide target = attackersWithout <= defendersWithout
            ? TeamSide.ATTACKERS : TeamSide.DEFENDERS;
        if (target == existing.teamSide()) {
            return RosterOperationResult.success("玩家已处于最平衡阵营", existing);
        }
        RosterOperationResult result = moveTeam(playerUuid, target, false, matchState, now);
        if (result.successful()) administratorRebalances++;
        return result;
    }

    @Override
    public synchronized Optional<TeamAssignment> assignment(UUID playerUuid) {
        TeamAssignment active = assignments.get(playerUuid);
        if (active != null) return Optional.of(active);
        Reservation reservation = reservations.get(playerUuid);
        return reservation == null ? Optional.empty() : Optional.of(reservation.assignment());
    }

    @Override
    public synchronized CombatRelation relation(UUID first, UUID second) {
        if (first.equals(second)) return assignments.containsKey(first)
            ? CombatRelation.SELF : CombatRelation.UNKNOWN;
        TeamAssignment a = assignments.get(first);
        TeamAssignment b = assignments.get(second);
        if (a == null || b == null || !a.matchId().equals(matchId) || !b.matchId().equals(matchId)) {
            return CombatRelation.UNKNOWN;
        }
        if (a.teamSide() != b.teamSide()) return CombatRelation.ENEMY;
        if (a.squadId().isPresent() && a.squadId().equals(b.squadId())) {
            return CombatRelation.SQUADMATE;
        }
        return CombatRelation.TEAMMATE;
    }

    @Override
    public synchronized Optional<TeamSide> teamOf(UUID playerUuid) {
        return assignment(playerUuid).map(TeamAssignment::teamSide);
    }

    @Override
    public synchronized Optional<SquadId> squadOf(UUID playerUuid) {
        return assignment(playerUuid).flatMap(TeamAssignment::squadId);
    }

    @Override
    public synchronized void beginMatch(UUID newMatchId) {
        assignments.clear();
        reservations.clear();
        switchCooldowns.clear();
        matchId = Objects.requireNonNull(newMatchId, "newMatchId");
        createdAt = Instant.now();
        tieBreakCursor = TeamSide.ATTACKERS;
        lastError = null;
        state = configuration.teamsEnabled() ? RosterState.ACTIVE : RosterState.DISABLED;
        revision++;
    }

    @Override
    public synchronized void clear() {
        assignments.clear();
        reservations.clear();
        switchCooldowns.clear();
        revision++;
    }

    @Override
    public synchronized RosterSnapshot snapshot() {
        List<TeamSnapshot> teams = List.of(teamSnapshot(TeamSide.ATTACKERS), teamSnapshot(TeamSide.DEFENDERS));
        List<SquadSnapshot> squads = new ArrayList<>();
        for (TeamSide side : TeamSide.values()) {
            for (SquadId id : SquadId.values()) {
                if (id.ordinal() < configuration.maximumSquadsPerTeam()) squads.add(squadSnapshot(side, id));
            }
        }
        return new RosterSnapshot(matchId, state, revision, teams, squads, assignments.size(),
            reservations.size(), lastError, state == RosterState.ACTIVE);
    }

    @Override
    public synchronized RosterMetricsSnapshot metrics() {
        RosterSnapshot snapshot = snapshot();
        int attackers = activeCount(TeamSide.ATTACKERS);
        int defenders = activeCount(TeamSide.DEFENDERS);
        return new RosterMetricsSnapshot(
            assignments.size(), reservations.size(), attackers, defenders,
            Math.abs(attackers-defenders),
            (int)snapshot.squads().stream().filter(s->s.currentMembers()>0).count(),
            (int)snapshot.squads().stream().filter(s->s.currentMembers()==s.maximumMembers()).count(),
            (int)snapshot.squads().stream().filter(s->s.leaderUuid()!=null).count(),
            automaticAssignments, reconnectRestores, assignmentFailures, capacityRejections,
            squadSwitches, squadSwitchFailures, administratorMoves, administratorRebalances,
            leaderTransfers, leaderAutomaticTransfers, staleReservationRemovals, rollbacks,
            invariantViolations
        );
    }

    @Override
    public synchronized RosterInvariantReport checkInvariants() {
        List<String> violations = new ArrayList<>();
        for (TeamSide side : TeamSide.values()) {
            if (teamLoad(side) > configuration.maximumPlayers(side)) {
                violations.add(side + " exceeds capacity");
            }
            for (SquadId id : SquadId.values()) {
                List<TeamAssignment> members = squadMembers(side, id);
                if (members.size() > configuration.maximumMembersPerSquad()) {
                    violations.add(side + "/" + id + " exceeds capacity");
                }
                long leaders = members.stream().filter(a->a.squadRole()==SquadRole.LEADER).count();
                if ((members.isEmpty() && leaders != 0) || (!members.isEmpty() && leaders != 1)) {
                    violations.add(side + "/" + id + " has invalid leader count");
                }
            }
        }
        for (TeamAssignment assignment : assignments.values()) {
            if (!assignment.matchId().equals(matchId)) violations.add("assignment matchId mismatch");
        }
        if (!violations.isEmpty()) {
            invariantViolations++;
            lastError = String.join("; ", violations);
            state = RosterState.FAILED;
        }
        return new RosterInvariantReport(violations.isEmpty(), violations);
    }

    public synchronized void failInitialization(String summary) {
        lastError = summary == null ? "Roster初始化失败" : summary;
        state = RosterState.FAILED;
        assignments.clear();
        reservations.clear();
        switchCooldowns.clear();
        revision++;
    }

    @Override
    public synchronized void close() {
        assignments.clear();
        reservations.clear();
        switchCooldowns.clear();
        state = RosterState.CLOSED;
        revision++;
    }

    private RosterOperationResult writable() {
        if (state == RosterState.DISABLED) {
            return RosterOperationResult.rejected(RosterFailure.DISABLED, "阵营系统未启用");
        }
        if (state != RosterState.ACTIVE) {
            return RosterOperationResult.rejected(RosterFailure.NOT_MODIFIABLE, "Roster 当前不可修改");
        }
        return null;
    }

    private boolean acceptsAdmission(MatchState matchState) {
        return matchState == MatchState.WAITING || matchState == MatchState.WARMUP
            || matchState == MatchState.PLAYING;
    }

    private boolean switchAllowed(MatchState state) {
        if (!configuration.switchingEnabled()) return false;
        return switch (state) {
            case WAITING -> configuration.switchDuringWaiting();
            case WARMUP -> configuration.switchDuringWarmup();
            case PLAYING -> configuration.switchDuringPlaying();
            default -> false;
        };
    }

    private TeamSide chooseTeam() {
        int attackers = teamLoad(TeamSide.ATTACKERS);
        int defenders = teamLoad(TeamSide.DEFENDERS);
        boolean attackersOpen = attackers < configuration.attackersMaximumPlayers();
        boolean defendersOpen = defenders < configuration.defendersMaximumPlayers();
        if (!attackersOpen && !defendersOpen) return null;
        if (!attackersOpen) return TeamSide.DEFENDERS;
        if (!defendersOpen) return TeamSide.ATTACKERS;
        if (attackers < defenders) return TeamSide.ATTACKERS;
        if (defenders < attackers) return TeamSide.DEFENDERS;
        return tieBreakCursor;
    }

    private SquadId chooseRestoredSquad(TeamAssignment previous, TeamSide side) {
        if (!configuration.squadsEnabled()) return null;
        SquadId old = previous.squadId().orElse(null);
        if (old != null && squadMembers(side, old).size() < configuration.maximumMembersPerSquad()) {
            return old;
        }
        return chooseSquad(side, null);
    }

    private SquadId chooseSquad(TeamSide side, SquadId excluded) {
        List<SquadId> ids = java.util.Arrays.stream(SquadId.values())
            .filter(id -> id.ordinal() < configuration.maximumSquadsPerTeam())
            .filter(id -> id != excluded)
            .filter(id -> squadMembers(side, id).size() < configuration.maximumMembersPerSquad())
            .toList();
        if (ids.isEmpty()) return null;
        if (configuration.preferExistingSquads()) {
            Optional<SquadId> existing = ids.stream()
                .filter(id -> !squadMembers(side,id).isEmpty())
                .min(Comparator.comparingInt((SquadId id)->squadMembers(side,id).size())
                    .thenComparingInt(Enum::ordinal));
            if (existing.isPresent()) return existing.get();
        }
        return ids.stream().min(Comparator.comparingInt(Enum::ordinal)).orElse(null);
    }

    private TeamAssignment assignment(
        UUID playerUuid, String name, TeamSide side, SquadId squad, Instant now,
        AssignmentSource source, boolean restored
    ) {
        SquadRole role = squad != null && squadMembers(side, squad).isEmpty()
            ? SquadRole.LEADER : SquadRole.MEMBER;
        return new TeamAssignment(playerUuid, name, matchId, side, Optional.ofNullable(squad),
            role, now, source, restored, true);
    }

    private TeamSnapshot teamSnapshot(TeamSide side) {
        return new TeamSnapshot(side, configuration.displayName(side), activeCount(side),
            reservedCount(side), configuration.maximumPlayers(side));
    }

    private SquadSnapshot squadSnapshot(TeamSide side, SquadId id) {
        List<TeamAssignment> members = squadMembers(side, id);
        List<SquadMemberSnapshot> snapshots = members.stream()
            .sorted(memberOrder())
            .map(a->new SquadMemberSnapshot(a.playerUuid(),a.currentName(),a.squadRole(),a.assignedAt(),true))
            .toList();
        return new SquadSnapshot(id, side, matchId, leader(side,id), members.size(),
            configuration.maximumMembersPerSquad(), snapshots,
            members.size()<configuration.maximumMembersPerSquad(), createdAt);
    }

    private List<TeamAssignment> squadMembers(TeamSide side, SquadId id) {
        return assignments.values().stream()
            .filter(a->a.teamSide()==side && a.squadId().orElse(null)==id)
            .toList();
    }

    private int activeCount(TeamSide side) {
        return (int) assignments.values().stream().filter(a->a.teamSide()==side).count();
    }

    private int reservedCount(TeamSide side) {
        return (int) reservations.values().stream()
            .filter(r->r.assignment().teamSide()==side).count();
    }

    private int teamLoad(TeamSide side) {
        return activeCount(side)+reservedCount(side);
    }

    private UUID leader(TeamSide side, SquadId id) {
        if (id == null) return null;
        return squadMembers(side,id).stream()
            .filter(a->a.squadRole()==SquadRole.LEADER)
            .map(TeamAssignment::playerUuid).findFirst().orElse(null);
    }

    private void normalizeLeaders() {
        for (TeamSide side : TeamSide.values()) {
            for (SquadId id : SquadId.values()) {
                List<TeamAssignment> members = squadMembers(side,id).stream()
                    .sorted(memberOrder()).toList();
                if (members.isEmpty()) continue;
                UUID chosen = members.stream().filter(a->a.squadRole()==SquadRole.LEADER)
                    .map(TeamAssignment::playerUuid).findFirst()
                    .orElse(members.getFirst().playerUuid());
                for (TeamAssignment member : members) {
                    setRole(member.playerUuid(),
                        member.playerUuid().equals(chosen) ? SquadRole.LEADER : SquadRole.MEMBER);
                }
            }
        }
    }

    private static Comparator<TeamAssignment> memberOrder() {
        return Comparator.comparing(TeamAssignment::assignedAt)
            .thenComparing(a->a.playerUuid().toString());
    }

    private void setRole(UUID uuid, SquadRole role) {
        if (uuid == null) return;
        TeamAssignment a = assignments.get(uuid);
        if (a != null && a.squadRole() != role) {
            assignments.put(uuid,new TeamAssignment(a.playerUuid(),a.currentName(),a.matchId(),
                a.teamSide(),a.squadId(),role,a.assignedAt(),a.source(),
                a.restoredAfterReconnect(),a.connected()));
        }
    }

    private boolean expired(Reservation reservation, Instant now) {
        return now.isAfter(reservation.disconnectedAt().plusSeconds(configuration.reconnectGraceSeconds()));
    }

    private RosterOperationResult fail(RosterFailure failure, String message) {
        assignmentFailures++;
        return RosterOperationResult.rejected(failure,message);
    }

    private RosterOperationResult failCapacity(RosterFailure failure, String message) {
        capacityRejections++;
        return fail(failure,message);
    }

    private RosterAdmissionPreparation preparationFailure(
        RosterFailure failure, String message, boolean capacity
    ) {
        assignmentFailures++;
        if (capacity) capacityRejections++;
        return RosterAdmissionPreparation.rejected(failure, message);
    }

    private RosterOperationResult switchFail(RosterFailure failure, String message) {
        squadSwitchFailures++;
        return fail(failure,message);
    }

    private record Reservation(TeamAssignment assignment, Instant disconnectedAt) {
    }
}
