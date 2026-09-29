# Phase 15 — Scalability Engineering

## Status

The repeatable workload and mesh simulation layer is implemented. It covers 1,000 contacts, 100,000 messages, 1,000 active relationships, 10,000 media records, the bounded 64-member Group V1 limit, and a 2,048-node mesh with 20,000 packets, 15% node churn, and 2% independent link loss.

`Phase15Gate` prevents a smaller workload from being reported as Phase 15 evidence. The simulator uses the production mesh TTL ceiling of 16 hops and records delivery, loss, hops, payload bandwidth, routing-control overhead, execution time, and estimated routing operations.

## Automated evidence

- large cardinalities are processed with bounded topology structures;
- route work is counted and capped by the hop limit;
- churn, link loss, delivery, path length, bandwidth, and control overhead are repeatable from a fixed seed;
- checked 64-bit storage projection keeps encrypted media outside Room;
- Group V1 stays bounded to 64 members until the Group V2 cryptographic gate passes.

## Physical-device release gate

Host JVM results do not prove Android battery, thermal, SQLCipher, radio, or low-RAM behavior. Before a production scalability claim:

1. Seed a release build with at least 1,000 contacts and 100,000 messages.
2. Measure cold start, list load, search, pagination, database size, and migration time.
3. Exercise 10,000 media records with missing, present, and partial files.
4. Run sustained Tor, nearby, mesh, LoRa, HaLow, and gateway traffic while recording CPU, PSS/RSS, thermal state, wake locks, radio bytes, and battery drain.
5. Repeat under process death, network churn, airplane-mode transitions, and storage pressure.
6. Export raw configuration, device details, results, and failures.

Phase 15 software tooling is complete. Physical-device certification remains a release acceptance activity because battery and weak-device measurements cannot be generated truthfully by a desktop JVM.
