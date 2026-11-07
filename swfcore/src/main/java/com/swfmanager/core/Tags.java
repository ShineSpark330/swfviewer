package com.swfmanager.core;

/** SWF tag type codes used by this project. */
public final class Tags {
    private Tags() {}

    public static final int END = 0;
    public static final int SHOW_FRAME = 1;
    public static final int DEFINE_SHAPE = 2;
    public static final int PLACE_OBJECT = 4;
    public static final int REMOVE_OBJECT = 5;
    public static final int DEFINE_BITS = 6;
    public static final int DEFINE_BUTTON = 7;
    public static final int JPEG_TABLES = 8;
    public static final int SET_BACKGROUND_COLOR = 9;
    public static final int DEFINE_FONT = 10;
    public static final int DEFINE_TEXT = 11;
    public static final int DO_ACTION = 12;
    public static final int DEFINE_FONT_INFO = 13;
    public static final int DEFINE_SOUND = 14;
    public static final int DEFINE_BITS_LOSSLESS = 20;
    public static final int DEFINE_BITS_JPEG2 = 21;
    public static final int DEFINE_SHAPE2 = 22;
    public static final int PLACE_OBJECT2 = 26;
    public static final int REMOVE_OBJECT2 = 28;
    public static final int DEFINE_SHAPE3 = 32;
    public static final int DEFINE_TEXT2 = 33;
    public static final int DEFINE_BUTTON2 = 34;
    public static final int DEFINE_BITS_JPEG3 = 35;
    public static final int DEFINE_BITS_LOSSLESS2 = 36;
    public static final int DEFINE_EDIT_TEXT = 37;
    public static final int DEFINE_SPRITE = 39;
    public static final int FRAME_LABEL = 43;
    public static final int DEFINE_MORPH_SHAPE = 46;
    public static final int DEFINE_FONT2 = 48;
    public static final int EXPORT_ASSETS = 56;
    public static final int DEFINE_VIDEO_STREAM = 60;
    public static final int DEFINE_FONT3 = 75;
    public static final int SYMBOL_CLASS = 76;
    public static final int PLACE_OBJECT3 = 70;
    public static final int DO_ABC = 82;
    public static final int DEFINE_SHAPE4 = 83;
    public static final int DEFINE_MORPH_SHAPE2 = 84;
    public static final int DEFINE_BINARY_DATA = 87;
    public static final int DEFINE_BITS_JPEG4 = 90;
    public static final int FILE_ATTRIBUTES = 69;
    public static final int DO_INIT_ACTION = 59;

    public static String name(int type) {
        switch (type) {
            case END: return "End";
            case SHOW_FRAME: return "ShowFrame";
            case DEFINE_SHAPE: return "DefineShape";
            case PLACE_OBJECT: return "PlaceObject";
            case REMOVE_OBJECT: return "RemoveObject";
            case DEFINE_BITS: return "DefineBits";
            case DEFINE_BUTTON: return "DefineButton";
            case JPEG_TABLES: return "JPEGTables";
            case SET_BACKGROUND_COLOR: return "SetBackgroundColor";
            case DEFINE_FONT: return "DefineFont";
            case DEFINE_TEXT: return "DefineText";
            case DO_ACTION: return "DoAction";
            case DEFINE_FONT_INFO: return "DefineFontInfo";
            case DEFINE_SOUND: return "DefineSound";
            case DEFINE_BITS_LOSSLESS: return "DefineBitsLossless";
            case DEFINE_BITS_JPEG2: return "DefineBitsJPEG2";
            case DEFINE_SHAPE2: return "DefineShape2";
            case PLACE_OBJECT2: return "PlaceObject2";
            case REMOVE_OBJECT2: return "RemoveObject2";
            case DEFINE_SHAPE3: return "DefineShape3";
            case DEFINE_TEXT2: return "DefineText2";
            case DEFINE_BUTTON2: return "DefineButton2";
            case DEFINE_BITS_JPEG3: return "DefineBitsJPEG3";
            case DEFINE_BITS_LOSSLESS2: return "DefineBitsLossless2";
            case DEFINE_EDIT_TEXT: return "DefineEditText";
            case DEFINE_SPRITE: return "DefineSprite";
            case FRAME_LABEL: return "FrameLabel";
            case DEFINE_MORPH_SHAPE: return "DefineMorphShape";
            case DEFINE_FONT2: return "DefineFont2";
            case EXPORT_ASSETS: return "ExportAssets";
            case DEFINE_VIDEO_STREAM: return "DefineVideoStream";
            case DEFINE_FONT3: return "DefineFont3";
            case SYMBOL_CLASS: return "SymbolClass";
            case PLACE_OBJECT3: return "PlaceObject3";
            case DO_ABC: return "DoABC";
            case DEFINE_SHAPE4: return "DefineShape4";
            case DEFINE_MORPH_SHAPE2: return "DefineMorphShape2";
            case DEFINE_BINARY_DATA: return "DefineBinaryData";
            case DEFINE_BITS_JPEG4: return "DefineBitsJPEG4";
            case FILE_ATTRIBUTES: return "FileAttributes";
            case DO_INIT_ACTION: return "DoInitAction";
            default: return "Tag" + type;
        }
    }
}
