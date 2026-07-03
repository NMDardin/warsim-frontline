package com.warsim.frontline.weapons;

import com.warsim.frontline.api.weapon.*;
import java.util.*;

public final class WeaponStateRegistry {
    private final Map<Key, MutableState> states = new HashMap<>();

    synchronized MutableState getOrCreate(
        UUID player, UUID match, WeaponDefinition definition
    ) {
        return states.computeIfAbsent(
            new Key(player, match, definition.weaponId()),
            ignored -> new MutableState(
                player, match, definition.weaponId(),
                definition.ammo().magazineSize(), definition.ammo().reserveAmmo()
            )
        );
    }

    public synchronized Optional<WeaponRuntimeState> find(
        UUID player, UUID match, WeaponId weapon
    ) {
        MutableState state = states.get(new Key(player, match, weapon));
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    synchronized Collection<MutableState> mutableStates() {
        return List.copyOf(states.values());
    }

    public synchronized void clearPlayer(UUID player) {
        states.keySet().removeIf(key -> key.player.equals(player));
    }

    public synchronized void clearWeapon(UUID player, UUID match, WeaponId weapon) {
        states.remove(new Key(player, match, weapon));
    }

    public synchronized void restore(WeaponRuntimeState snapshot) {
        MutableState state = new MutableState(
            snapshot.playerUuid(),
            snapshot.matchId(),
            snapshot.weaponId(),
            snapshot.magazineAmmo(),
            snapshot.reserveAmmo()
        );
        state.reload = snapshot.reloadState();
        state.reloadStarted = snapshot.reloadStartedAtNanos();
        state.reloadCompletes = snapshot.reloadCompletesAtNanos();
        state.nextShot = snapshot.nextAllowedShotAtNanos();
        state.shots = snapshot.shotsFired();
        state.revision = snapshot.revision();
        states.put(new Key(snapshot.playerUuid(), snapshot.matchId(), snapshot.weaponId()), state);
    }

    public synchronized void clearMatch(UUID match) {
        states.keySet().removeIf(key -> key.match.equals(match));
    }

    public synchronized int size() {
        return states.size();
    }

    public synchronized void clear() {
        states.clear();
    }

    private record Key(UUID player, UUID match, WeaponId weapon) {}

    static final class MutableState {
        final UUID player;
        final UUID match;
        final WeaponId weapon;
        int magazine;
        int reserve;
        ReloadState reload = ReloadState.READY;
        long reloadStarted;
        long reloadCompletes;
        long nextShot;
        long shots;
        long revision;

        MutableState(UUID player, UUID match, WeaponId weapon, int magazine, int reserve) {
            this.player = player;
            this.match = match;
            this.weapon = weapon;
            this.magazine = magazine;
            this.reserve = reserve;
        }

        WeaponRuntimeState snapshot() {
            return new WeaponRuntimeState(
                player, match, weapon, magazine, reserve, reload,
                reloadStarted, reloadCompletes, nextShot, shots, revision
            );
        }
    }
}
