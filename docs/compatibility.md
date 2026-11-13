# Compatibility

Which Minecraft versions LOD Server Support is built for, and how much attention each gets. This
page is kept by hand on main. Each branch states its own facts in
`config/compatibility/line.json`, and its build checks them against the build definitions.

## Support tiers

| Tier | Versions | What it gets |
| --- | --- | --- |
| full | 26.3, 1.21.1 | every release, including betas |
| maintained | 26.2, 1.21.11 | every stable release: fixes, and features that port cleanly |
| frozen | 26.1, 1.21.10 | 0.21.0 stays published; crash and security fixes only |

The tiers follow where players are: 26.3 and 1.21.1 carried 77 % of the downloads of 0.20.0, and
26.1 and 1.21.10 together under 4 % ([decision 0001](decisions/0001-line-tiers.md)).

## Platforms by version

| Minecraft | Branch | Java | Fabric, Paper | Folia | NeoForge server and client | Far players drawn on NeoForge | Sponge |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 26.3 | `main` | 25 | yes | experimental | yes | no | experimental |
| 1.21.1 | `support/mc1.21.1` | 21 | yes | no | yes | yes | up to 0.21.0 |
| 26.2 | `support/mc26.2` | 25 | yes | experimental | yes | no | up to 0.21.0 |
| 1.21.11 | `support/mc1.21.11-v0.14` | 21 | yes | experimental | no | no | up to 0.21.0 |
| 26.1 | `support/mc26.1-v0.14` | 25 | yes | experimental | yes | no | 0.21.0 |
| 1.21.10 | `support/mc1.21.10` | 21 | yes | no | no | no | 0.21.0 |

- Clients use the Fabric mod on every version. A NeoForge client also works where NeoForge is
  listed.
- Folia is experimental wherever it is listed. A bounded soak does not remove that label.
- The Sponge plugin (SpongeVanilla) is experimental and, from 0.22.0, built for 26.3 only.
- Fabric client gametests run on every version except 1.21.1.

## What "tested" means here

A version being built is not proof that a combination of mods works. Tested combinations are the
ones the release gates and the real-stack checks ran; each release's preparation record under
`docs/planning/` lists them. Dependency profiles and dated validation records, where a branch has
them, are under that branch's `config/compatibility/`.
