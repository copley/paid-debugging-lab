# Case Study 023 — CloudFormation ICMPv6 security-group false positive

## Upstream issue

- CDK report: [`aws/aws-cdk#38422`](https://github.com/aws/aws-cdk/issues/38422)
- Likely implementation repo: [`aws-cloudformation/cloudformation-validate`](https://github.com/aws-cloudformation/cloudformation-validate)

## Symptom

Synthesizing a CDK security-group rule created with `ec2.Port.allIcmpV6()` produces this warning:

```text
CloudFormation-Validate::W3687
['FromPort', 'ToPort'] are ignored when using 'IpProtocol' value 'icmpv6'
```

The generated rule uses `FromPort: -1` and `ToPort: -1`, which are valid for ICMP and ICMPv6 and mean all message types/codes.

## Minimal reproduction

```ts
import * as ec2 from 'aws-cdk-lib/aws-ec2';
import { Stack } from 'aws-cdk-lib';

const stack = new Stack();
const securityGroup = new ec2.SecurityGroup(stack, 'SecurityGroup', {
  vpc: ec2.Vpc.fromLookup(stack, 'Vpc', { isDefault: true }),
});

securityGroup.addIngressRule(
  ec2.Peer.anyIpv6(),
  ec2.Port.allIcmpV6(),
);
```

Expected: no `W3687` warning.

Actual: the validator classifies `icmpv6` as a protocol for which ports are ignored.

## Root cause

The validator's protocol sets include ICMP, TCP, and UDP, but omit ICMPv6.

The current Rego rule defines protocols that require `FromPort` and `ToPort` as:

```rego
_sg_port_required_protocols := {
  "1", "icmp",
  "6", "tcp",
  "17", "udp",
  "TCP", "UDP", "ICMP"
}
_sg_port_required_numbers := {1, 6, 17}
```

The inverse warning rule treats every other protocol as ignoring ports:

```rego
_sg_protocol_ignores_ports(proto) if {
  is_string(proto)
  not proto in {"1", "icmp", "6", "tcp", "17", "udp", "TCP", "UDP", "ICMP"}
}

_sg_protocol_ignores_ports(proto) if {
  is_number(proto)
  not proto in {1, 6, 17}
}
```

ICMPv6 uses protocol number `58`, and CloudFormation also accepts the string form `icmpv6`. Because neither representation is included, the rule emits `W3687` when valid `-1` port values are present.

This is below the CDK construct layer. A CDK-only suppression would hide the false positive but would not repair templates authored without CDK.

## Small PR direction

Update both protocol sets in:

```text
src/rego-engine/handwritten/rego/resources/ec2/sg_protocol_ports.rego
```

Candidate change:

```rego
_sg_port_required_protocols := {
  "1", "icmp",
  "6", "tcp",
  "17", "udp",
  "58", "icmpv6",
  "TCP", "UDP", "ICMP", "ICMPV6"
}
_sg_port_required_numbers := {1, 6, 17, 58}
```

The `_sg_protocol_ignores_ports` allow-set must receive the same additions so the required/error and ignored/warning rules remain complementary.

## Regression tests

Add cases for both embedded and standalone security-group resources:

1. `IpProtocol: icmpv6`, `FromPort: -1`, `ToPort: -1` — no `W3687`.
2. `IpProtocol: "58"`, `FromPort: -1`, `ToPort: -1` — no `W3687`.
3. `IpProtocol: 58`, `FromPort: -1`, `ToPort: -1` — no `W3687`.
4. ICMPv6 without ports — retain the required-port diagnostic if that is the validator's intended contract.
5. An unrelated protocol such as `gre` with ports — `W3687` still fires.
6. Existing ICMP/TCP/UDP behavior remains unchanged.

## Verification path

```text
1. Run the validator rule test suite.
2. Validate a raw CloudFormation security-group ingress resource using icmpv6/-1/-1.
3. Validate the CDK-generated template from aws/aws-cdk#38422.
4. Confirm W3687 remains for a protocol whose FromPort/ToPort values are genuinely ignored.
```

I have inspected the issue and the upstream rule source. I have not executed the upstream Rust/Rego test suite in this workflow run, so the patch should not be presented as test-verified yet.

## Draft diagnostic comment

> I traced this below the CDK construct layer into the CloudFormation validator's security-group protocol sets.
>
> The `W3687` rule treats ports as meaningful only for protocol numbers `1`, `6`, and `17` and the string forms `icmp`, `tcp`, and `udp`. ICMPv6 (`58` / `icmpv6`) is absent from both the required-port set and the inverse ignored-port allow-set, so a valid `FromPort: -1` / `ToPort: -1` rule is classified as using ignored ports.
>
> A focused upstream fix is to add `58`, `"58"`, `icmpv6`, and the accepted uppercase form to both complementary protocol sets in `sg_protocol_ports.rego`. Regression coverage should include string and numeric protocol forms plus a control case such as GRE that must continue producing `W3687` when ports are supplied.
>
> This belongs in `aws-cloudformation/cloudformation-validate`; suppressing it only in CDK would leave the same false positive for raw CloudFormation templates.

## Prevention note

Validation rules based on protocol allow-lists should test all service-supported symbolic and numeric forms, and complementary predicates such as “requires ports” and “ignores ports” should derive from one shared protocol definition where possible. That prevents the two sets from drifting as protocol support evolves.
