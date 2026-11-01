package es.uma.urbanpulse.incidents.dto;

public record IncidentLocation(
        Double latitude,
        Double longitude,
        Double accuracy,
        String address,
        String neighborhood,
        String district) {}
