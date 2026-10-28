# Local server package (release)

Built by `tools/build-local-package.ps1 -Version X.Y.Z`. Contents: compose with the prebuilt image
`ghcr.io/gleb-stasuyk/l2start-server:X.Y.Z` (public GHCR), geodata, overrides (solo + `overrides-local`), `settings.env`,
`modules.env`, install/start/stop scripts. Clean DB: `db-init` imports only the stock schema into an empty volume
`l2l-db-data`; no accounts (AutoCreateAccounts = True). Project name `l2start-local`, containers `l2l-*`
(no clash with the dev stack `l2s-*`, but the same ports 2106/7777 by default: one server at a time).

Publishing (only after Gleb's OK): `docker push ghcr.io/gleb-stasuyk/l2start-server:X.Y.Z`, make the package public,
then `gh release create server-vX.Y.Z build/local-package/l2start-local-X.Y.Z.zip`.

Launcher-owned settings (`settings.env`, read by `docker/local-features.sh`):

| Key | Meaning |
|---|---|
| `L2S_CLASS_MASTER` | `off` or any of `1,2,3`: stock ClassMaster popup (class-choice dialog at 20/40/76), free |
| `L2S_STOCK_NPC_BUFFER` | `on/off`: stock Scheme Buffer NPC 50008 at the gatekeepers of Gludio/Dion/Giran/Aden/Goddard |
| `L2S_BUFF_DURATION` | seconds for every skill of `CommunityAvailableBuffs` + `SchemeBufferSkills.xml` (`EnableModifySkillDuration` + `SkillDurationList`); 0 = stock |
