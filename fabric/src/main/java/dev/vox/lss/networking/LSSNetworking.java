package dev.vox.lss.networking;

import net.minecraft.resources.ResourceLocation;

public class LSSNetworking {
    // Client -> Server packet identifiers
    public static final ResourceLocation HANDSHAKE_C2S = new ResourceLocation("lss", "handshake");
    public static final ResourceLocation CHUNK_REQUEST_C2S = new ResourceLocation("lss", "chunk_request");
    public static final ResourceLocation CANCEL_REQUEST_C2S = new ResourceLocation("lss", "cancel_request");

    // Server -> Client packet identifiers
    public static final ResourceLocation SESSION_CONFIG_S2C = new ResourceLocation("lss", "session_config");
    public static final ResourceLocation CHUNK_SECTION_S2C = new ResourceLocation("lss", "chunk_section");
    public static final ResourceLocation REQUEST_COMPLETE_S2C = new ResourceLocation("lss", "request_complete");
    public static final ResourceLocation COLUMN_UP_TO_DATE_S2C = new ResourceLocation("lss", "column_up_to_date");
    public static final ResourceLocation DIRTY_COLUMNS_S2C = new ResourceLocation("lss", "dirty_columns");

    public static void registerPayloads() {
        // In 1.20.1 this method is no longer needed for PayloadTypeRegistry, 
        // handlers are registered separately.
    }
}