use anyhow::{Context, Result, bail, ensure};
use std::ffi::CString;
use std::fs;
use std::path::{Component, Path, PathBuf};

use crate::defs;

const CONFIG_FILE: &str = "/data/adb/ksu/module_storage_path";
const CURRENT_FILE: &str = "/data/adb/ksu/module_storage_current";

fn normalize(path: &str) -> Result<PathBuf> {
    let path = path.trim().trim_end_matches('/');
    ensure!(!path.is_empty(), "module storage path is empty");
    let candidate = Path::new(path);
    ensure!(candidate.is_absolute(), "module storage path must be absolute");
    ensure!(path.starts_with("/data/"), "module storage path must be under /data");
    ensure!(
        candidate
            .components()
            .all(|part| matches!(part, Component::RootDir | Component::Normal(_))),
        "module storage path contains an unsafe component"
    );

    let normalized = candidate.to_path_buf();
    ensure!(
        normalized != Path::new("/data") && normalized != Path::new("/data/adb"),
        "module storage path is too broad"
    );
    for reserved in [
        Path::new("/data/adb/ksu"),
        Path::new("/data/adb/metamodule"),
        Path::new(defs::MODULE_UPDATE_DIR.trim_end_matches('/')),
    ] {
        ensure!(
            normalized != reserved && !normalized.starts_with(reserved),
            "module storage path overlaps a reserved KernelSU directory"
        );
    }
    ensure!(
        !normalized.starts_with(Path::new(defs::MODULE_DIR)),
        "module storage path cannot be inside the compatibility mount point"
    );
    Ok(normalized)
}

fn read_path(file: &str) -> Option<PathBuf> {
    fs::read_to_string(file)
        .ok()
        .and_then(|value| normalize(&value).ok())
}

pub fn desired_path() -> PathBuf {
    read_path(CONFIG_FILE)
        .unwrap_or_else(|| PathBuf::from(defs::MODULE_DIR.trim_end_matches('/')))
}

fn current_path() -> PathBuf {
    read_path(CURRENT_FILE)
        .unwrap_or_else(|| PathBuf::from(defs::MODULE_DIR.trim_end_matches('/')))
}

fn update_path(module_path: &Path) -> Result<PathBuf> {
    let name = module_path
        .file_name()
        .and_then(|name| name.to_str())
        .context("module storage path has no final directory name")?;
    Ok(module_path.with_file_name(format!("{name}_update")))
}

fn write_atomic(file: &str, value: &Path) -> Result<()> {
    fs::create_dir_all(defs::WORKING_DIR)?;
    let tmp = format!("{file}.tmp.{}", std::process::id());
    fs::write(&tmp, format!("{}\n", value.display()))?;
    fs::rename(&tmp, file)?;
    Ok(())
}

fn directory_is_empty(path: &Path) -> Result<bool> {
    if !path.exists() {
        return Ok(true);
    }
    Ok(fs::read_dir(path)?.next().is_none())
}

pub fn set_desired_path(path: &str) -> Result<PathBuf> {
    let selected = if path.trim().eq_ignore_ascii_case("default") {
        PathBuf::from(defs::MODULE_DIR.trim_end_matches('/'))
    } else {
        normalize(path)?
    };
    let current = current_path();
    ensure!(
        selected == current
            || (!selected.starts_with(&current) && !current.starts_with(&selected)),
        "new and current storage directories cannot contain each other"
    );
    if selected != current && selected != Path::new(defs::MODULE_DIR.trim_end_matches('/')) {
        ensure!(
            directory_is_empty(&selected)?,
            "selected directory must be empty before migration"
        );
    }
    fs::create_dir_all(&selected)?;
    fs::create_dir_all(update_path(&selected)?)?;
    write_atomic(CONFIG_FILE, &selected)?;
    Ok(selected)
}

fn migrate_directory(source: &Path, destination: &Path) -> Result<()> {
    if source == destination {
        return Ok(());
    }
    fs::create_dir_all(source)?;
    fs::create_dir_all(destination)?;
    ensure!(
        directory_is_empty(destination)?,
        "migration destination {} is not empty",
        destination.display()
    );
    for entry in fs::read_dir(source)? {
        let entry = entry?;
        fs::rename(entry.path(), destination.join(entry.file_name())).with_context(|| {
            format!(
                "failed to migrate {} to {}",
                entry.path().display(),
                destination.display()
            )
        })?;
    }
    Ok(())
}

fn is_mountpoint(path: &Path) -> bool {
    let wanted = path.to_string_lossy();
    fs::read_to_string("/proc/self/mountinfo").is_ok_and(|mountinfo| {
        mountinfo.lines().any(|line| {
            line.split_whitespace()
                .nth(4)
                .is_some_and(|mountpoint| mountpoint == wanted)
        })
    })
}

fn bind_mount(source: &Path, target: &Path) -> Result<()> {
    if is_mountpoint(target) {
        return Ok(());
    }
    fs::create_dir_all(source)?;
    fs::create_dir_all(target)?;
    let source = CString::new(source.as_os_str().as_encoded_bytes())?;
    let target = CString::new(target.as_os_str().as_encoded_bytes())?;
    let result = unsafe {
        libc::mount(
            source.as_ptr(),
            target.as_ptr(),
            std::ptr::null(),
            libc::MS_BIND | libc::MS_REC,
            std::ptr::null(),
        )
    };
    if result != 0 {
        bail!("bind mount failed: {}", std::io::Error::last_os_error());
    }
    Ok(())
}

/// Move module data to the selected physical directory and expose it through the
/// original /data/adb/modules paths. Existing modules and third-party scripts keep
/// their original paths while the actual files live in the user-selected location.
pub fn prepare() -> Result<PathBuf> {
    let desired = desired_path();
    let current = current_path();
    if desired != current {
        migrate_directory(&current, &desired)?;
        migrate_directory(&update_path(&current)?, &update_path(&desired)?)?;
        write_atomic(CURRENT_FILE, &desired)?;
    } else if !Path::new(CURRENT_FILE).exists() {
        write_atomic(CURRENT_FILE, &desired)?;
    }

    let compatibility = Path::new(defs::MODULE_DIR.trim_end_matches('/'));
    if desired != compatibility {
        bind_mount(&desired, compatibility)?;
        bind_mount(
            &update_path(&desired)?,
            Path::new(defs::MODULE_UPDATE_DIR.trim_end_matches('/')),
        )?;
    }
    Ok(desired)
}

#[cfg(test)]
mod tests {
    use super::normalize;

    #[test]
    fn accepts_safe_data_paths() {
        assert!(normalize("/data/adb/night/modules").is_ok());
        assert!(normalize("/data/local/ksu-modules").is_ok());
    }

    #[test]
    fn rejects_unsafe_paths() {
        for path in [
            "/",
            "/sdcard/modules",
            "/data/../system",
            "/data/adb/ksu/modules",
            "/data/adb/modules/custom",
        ] {
            assert!(normalize(path).is_err(), "accepted unsafe path: {path}");
        }
    }
}
