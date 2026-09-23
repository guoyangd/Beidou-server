package org.gms.model.dto;

import lombok.*;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ChrOnlineListRtnDTO {
    private int world;
    private int channel;
    private int id;
    private String name;
    private int map;
    private String mapName;
    private String streetName;
    private int job;
    private String jobName;
    private int level;
    private int gm;
}
