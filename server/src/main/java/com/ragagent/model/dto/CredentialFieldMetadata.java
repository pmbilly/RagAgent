package com.ragagent.model.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** CredentialFieldMetadata（对照 Go dto.CredentialFieldMetadata） */
public record CredentialFieldMetadata(@JsonProperty("configured") boolean configured) {
}
