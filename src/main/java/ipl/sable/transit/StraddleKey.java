package ipl.sable.transit;

import java.util.UUID;

/** Identifies one construction's contact session with one portal face. */
public record StraddleKey(UUID subLevelUuid, UUID portalUuid) {}
