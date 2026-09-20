# Physical host storage guard

Rig create checks storage after its read-only input plan succeeds and before creating run directories or copying files. It counts each explicitly staged copy, reserves at least 8 GiB for run growth (or the estimated copy size if larger), and retains 50 GiB free on both the Linux filesystem and the physical host volume. The immutable estimate is retained in the run manifest; start requires it and checks capacity again.

On WSL, the guard resolves the current distribution's VHDX through the Windows Lxss registry and queries its actual volume. If registry discovery is unavailable, `LSS_RIG_WSL_VHD_PATH` can name the actual Windows VHDX path. Unknown host location, an inaccessible volume, or the absence of a unique verified DrvFS monitor rejects the run. Linux free space alone cannot establish physical host capacity.

Startup readiness and observation loops sample the verified Linux and host mounts at a 60-second cadence using filesystem capacity calls. No Windows subprocess or content scan runs during observation. Falling below the 50 GiB reserve raises an error through the existing owned-process cleanup path. These are conservative capacity budgets, not guarantees against unrelated writers filling the disk between samples.

`doctor` reports actual host storage readiness and an explicit error on failure. Its estimate covers known runtime stages only; create additionally counts selected profile artifacts. Preflight receipts and periodic health samples live under the run's `evidence` directory. Doctor performs no staging, game launch, or authentication.
