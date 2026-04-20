# DisasterSOS: Narrow Vertical Flowchart

```mermaid
graph TD
    %% Narrow Styling
    classDef sys fill:#e7f5ff,stroke:#228be6,stroke-width:2px;
    classDef vic fill:#fff4e6,stroke:#ff922b,stroke-width:2px;
    classDef res fill:#ebfbee,stroke:#40c057,stroke-width:2px;

    %% Core System (Tall Stack)
    Start([Start]) --> Svc[Mesh Service]:::sys
    Svc --> Net[Hybrid Mesh]:::sys

    %% Victim Flow
    Net --> V_Adapt[Adaptive Gossip]:::vic
    V_Adapt --> V_Packet[SOS Packet]:::vic
    V_Packet --> V_Broad[Broadcast]:::vic

    %% Relay Logic
    V_Broad --> M_Catch[Signal Caught]:::sys
    M_Catch --> M_Router{Router}:::sys
    M_Router -- New --> M_Relay[Relay]:::sys
    M_Relay --> M_Catch

    %% Rescuer Flow
    M_Relay -.-> R_Recv[SOS Alert]:::res
    R_Recv --> R_Guide[Rescue Guide]:::res
    R_Guide --> End([Success]):::res
```

### Layout Note:
This flowchart is optimized for **narrow vertical spaces**. It stacks every step on top of the other to keep the width to a minimum while showing the clear progression from launch to rescue.
