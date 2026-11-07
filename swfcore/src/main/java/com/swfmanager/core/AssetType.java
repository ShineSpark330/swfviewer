package com.swfmanager.core;

public enum AssetType {
    SHAPE("Shapes"),
    MORPH_SHAPE("Morph shapes"),
    IMAGE("Images"),
    SOUND("Sounds"),
    SPRITE("Sprites"),
    FONT("Fonts"),
    TEXT("Text"),
    BUTTON("Buttons"),
    BINARY("Binary data"),
    SCRIPT("Scripts"),
    VIDEO("Video"),
    OTHER("Other");

    public final String label;

    AssetType(String label) {
        this.label = label;
    }
}
