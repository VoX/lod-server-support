package dev.vox.lss.common.processing;

public record PendingRequest(int requestId, int cx, int cz, RequestType type) {}
