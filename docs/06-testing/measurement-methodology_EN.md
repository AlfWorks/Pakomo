# Measurement Methodology

English | [简体中文](measurement-methodology.md)

The weak-network parameters Pakomo sets do not necessarily map one-to-one to the numbers shown by third-party speed tests. This document explains at which layer Pakomo samples, whether upstream and downstream are independent, at which layer loss occurs, and the recommended verification method and tolerance.

## Reasons for the discrepancy

Take a 5% packet-loss setting as an example:

- Pakomo's upstream and downstream may each approach 5%, while a third-party tool may combine both directions and show close to 10%.
- Tools often classify timeouts, late packets, and truly dropped packets together, with a different measurement definition.
- Application-layer retries change the final statistics; a connection that succeeds on retry may show no loss at all.
- A speed test's own probing model — e.g. the number of concurrent streams, probe duration, and statistics window — is not at the same layer as Pakomo's injection point.

## Where Pakomo samples

- **Injection point**: shaping and faults act on each connection of the local SOCKS5 relay, i.e. the connection layer — neither at the NIC layer nor at the application-request layer.
- **Direction**: upstream and downstream are handled independently. Simple mode splits one overall (round-trip) value across the two directions; advanced mode sets each direction independently.
- **Loss layer**: dropping acts at the relay's write and chunking layer, targeting the data transfer of a matched connection, rather than randomly dropping underlying IP packets.
- **Latency vs. slow response**: ordinary latency represents transit time, whereas slow response is an independent downstream-hold model (see [Fault Models](../01-capabilities/fault-models_EN.md)); late packets should not be counted as loss.

## Verification advice

- **Verify protocol behavior first, then look at numbers**. For example, loss should be verified by whether the relay proportionally drops data on matched connections, connection reset by capturing the RST, network blackout by confirming refusal, suspension, or timeout, and DNS by confirming the RCODE. Correct protocol behavior is the pass condition; third-party numbers are for reference only.
- **Verify per direction**: measure upstream and downstream separately; do not compare a combined bidirectional value directly against Pakomo's setting.
- **Accept a reasonable tolerance**: under a random model and a finite statistics window, a single measurement deviates; take the average of enough samples across multiple measurements rather than requiring an exact match to the set value.
- **State the sample layer**: specify whether the verification tool counts at the packet, flow, connection, or application-request layer, since different layers yield different conclusions.

## Promises not made

- No promise that any third-party speed test's displayed value equals Pakomo's set value.
- No promise that a late packet is definitely counted, or definitely not counted, as loss by a tool — that depends on the tool.
- No promise that the final success rate after application-layer retries reflects the underlying loss rate.
