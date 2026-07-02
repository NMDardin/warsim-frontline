package com.warsim.frontline.api.classes;

public enum EmptyEquipmentReference implements EquipmentReference {
    INSTANCE;

    @Override
    public boolean empty() {
        return true;
    }
}
