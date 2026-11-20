# Upload Guardian to Modrinth

Use **Actions → Modrinth upload → Run workflow** after pushing the release source.
The default release is `0.4.0-alpha.99`. Select `all` for Minecraft and loader to
prepare all 22 builds. Leave **dry_run** checked for the first run.

## Organization setup

- Keep the existing organization secret named `MODRINTH_TOKEN`. Allow the
  `BareMinimumStudios/guardian` repository to use it. Do not copy the token into source,
  a workflow input or a command argument.
- Set the repository variable `MODRINTH_PROJECT_ID` to **Guardian's** project ID or
  slug, or fill in the workflow's `project_id` input. The shared organization token
  does not make another mod's project ID appropriate for Guardian.
- Guardian's Modrinth source URL must be `https://github.com/BareMinimumStudios/guardian`.
  The token needs permission to create versions on that project.

The workflow checks secret availability when it runs. Local preparation cannot
inspect or retrieve GitHub organization secrets.

## Check, then upload

1. Push the release source, including the workflows and scripts.
2. Run **Modrinth upload** with **dry_run** checked. It builds and tests both loaders,
   verifies runtime jars and hashes, checks the destination and lists pending uploads.
   Read the run's summary and download its prepared jars or upload plan if needed.
3. Run it again with **dry_run** unchecked to upload alpha versions through mc-publish.

The workflow uploads a separate version for each Minecraft/loader combination.
Fabric lists Fabric API, Fabric Language Kotlin and Fzzy Config as required;
NeoForge lists Kotlin for Forge and Fzzy Config. Java is 21 for 1.21.x and 25 for
26.x. Clients do not need Guardian. WorldEdit's separate GPL adapter is excluded
from the core project upload and remains Fabric 1.21.1 only.

Uploads do not approve the project or change its moderation status. Draft and
pending projects are checked normally; if Modrinth rejects an upload, the workflow
fails with the platform error. It does not bypass review or change project visibility.

## Run from PowerShell

With GitHub CLI installed and signed in, use the wrapper from the repository root:

```powershell
.\scripts\publish-modrinth.ps1
.\scripts\publish-modrinth.ps1 -Upload
```

The first command dispatches a dry run. `-Upload` enables uploads. Add `-Ref branch-name`
when the release source is on a different pushed branch, or `-ProjectId guardian-project-id`
to override the project variable. The wrapper passes no Modrinth token; Actions uses
the existing organization secret.

## Retry a partial upload

Run the workflow again for the same release. Versions with matching bytes, loader,
Minecraft target and required dependencies are skipped. A matching version number
with different files or metadata is refused; existing versions are not replaced or
deleted. Select one Minecraft version and/or loader to limit a retry.

If a network error occurs after Modrinth accepted a file, rerunning preflight checks
the remote result before another upload. The workflow serializes Modrinth runs and
uses at most two upload jobs at once.

## Local artifact preparation

After building the selected target, Python 3.11+ can prepare it without credentials:

```text
python scripts/prepare_modrinth.py prepare --minecraft 1.21.1
python scripts/prepare_modrinth.py verify --minecraft 1.21.1
```

These commands validate and copy core runtime jars into `build/modrinth`, write their
hash manifests and extract the dated release notes. They do not build or upload files.
`preflight` performs read-only Modrinth checks using `MODRINTH_TOKEN` from the environment;
only the mc-publish workflow step performs uploads.

Alpha.99 is a testing release. Back up before its schema-27 upgrade. Automated
recovery validation does not replace connected-client or production-modpack acceptance.
