package com.warsim.frontline.api.classes;

public interface CombatLoadoutProvisioningService {
    boolean isAvailable();

    default java.util.UUID providerInstanceId() {
        return new java.util.UUID(0, 0);
    }

    LoadoutValidationResult validateLoadout(ClassEquipmentDefinition definition);

    LoadoutProvisionResult prepareLoadout(LoadoutPreparationRequest request);

    LoadoutProvisionResult grantPreparedLoadout(LoadoutPreparationToken token);

    default LoadoutProvisionResult cancelPreparedLoadout(LoadoutPreparationToken token, String reason) {
        return LoadoutProvisionResult.success("No active prepared token", null);
    }

    LoadoutProvisionResult clearManagedLoadout(ManagedLoadoutClearRequest request);

    LoadoutProvisionResult resetCombatLifeState(CombatLifeResetRequest request);
}
