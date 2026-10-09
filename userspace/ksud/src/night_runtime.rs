use anyhow::{Context, Result};
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::time::{SystemTime, UNIX_EPOCH};

const RUNTIME_DIR: &str = "/data/adb/night";
const STATE_FILE: &str = "/data/adb/night/runtime_state";
const STATE_TMP: &str = "/data/adb/night/.runtime_state.tmp";
const SCHEMA_VERSION: u32 = 1;

fn read_boot_id() -> String {
    fs::read_to_string("/proc/sys/kernel/random/boot_id")
        .unwrap_or_else(|_| "unavailable".to_owned())
        .trim()
        .to_owned()
}

fn unix_time() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_secs())
}

pub fn record_phase(phase: &str, detail: &str) -> Result<()> {
    fs::create_dir_all(RUNTIME_DIR).context("create Night runtime directory")?;
    fs::set_permissions(RUNTIME_DIR, fs::Permissions::from_mode(0o700))
        .context("protect Night runtime directory")?;

    let state = format!(
        "engine=Night Exclusive Runtime\nsource=Night exclusive derivative (based on KernelSU)\npurpose=personal hobby development\nruntime_schema={SCHEMA_VERSION}\nphase={phase}\ndetail={detail}\nboot_id={}\nupdated_unix={}\n",
        read_boot_id(),
        unix_time(),
    );
    fs::write(STATE_TMP, state).context("write Night runtime state")?;
    fs::set_permissions(STATE_TMP, fs::Permissions::from_mode(0o600))
        .context("protect Night runtime state")?;
    fs::rename(STATE_TMP, STATE_FILE).context("publish Night runtime state")?;
    Ok(())
}

pub fn status() -> String {
    let mut state = fs::read_to_string(STATE_FILE).unwrap_or_else(|_| {
        format!(
            "engine=Night Exclusive Runtime\nsource=Night exclusive derivative (based on KernelSU)\npurpose=personal hobby development\nruntime_schema={SCHEMA_VERSION}\nphase=not-started\ndetail=waiting for post-fs-data\nboot_id={}\nupdated_unix=0\n",
            read_boot_id(),
        )
    });
    if !state.ends_with('\n') {
        state.push('\n');
    }
    state.push_str(&format!(
        "module_storage={}\nstate_file={}\n",
        crate::module_storage::desired_path().display(),
        Path::new(STATE_FILE).display(),
    ));
    state
}
