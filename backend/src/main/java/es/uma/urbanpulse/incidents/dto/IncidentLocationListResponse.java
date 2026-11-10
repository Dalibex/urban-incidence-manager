package es.uma.urbanpulse.incidents.dto;

import java.util.List;

/**
 * {@code IncidentLocationListResponse}: the positions for the map, the most relevant first.
 *
 * @param truncated {@code true} when more incidents match than the server's cap, so some are left
 *     out
 */
public record IncidentLocationListResponse(
        List<IncidentPositionResponse> content, boolean truncated) {

    public IncidentLocationListResponse {
        content = List.copyOf(content);
    }
}
