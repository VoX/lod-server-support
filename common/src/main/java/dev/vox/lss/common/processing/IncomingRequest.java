package dev.vox.lss.common.processing;

public record IncomingRequest(int requestId, int cx, int cz, long clientTimestamp) {}
