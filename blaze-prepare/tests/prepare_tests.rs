// Blaze Launcher — on-device 26.3 engine-data preparer tests
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

use blaze_prepare::files::{cleanup_partials, sha1_bytes, sha256_bytes, CancelFlag};
use blaze_prepare::resources::{
    is_generated_engine_file, is_prepared, package_resources_zip, run_phase1, run_phase2_finalize,
    PrepareConfig,
};
use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::{Instant, SystemTime, UNIX_EPOCH};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

struct TempDir(PathBuf);

impl TempDir {
    fn new(name: &str) -> Self {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "blaze-prepare-{name}-{}-{nanos}",
            std::process::id()
        ));
        fs::create_dir_all(&path).unwrap();
        Self(path)
    }

    fn path(&self) -> &Path {
        &self.0
    }
}

impl Drop for TempDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn build_zip(entries: &[(&str, &[u8])]) -> Vec<u8> {
    let mut cursor = std::io::Cursor::new(Vec::new());
    {
        let mut zip = ZipWriter::new(&mut cursor);
        let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
        for (name, data) in entries {
            zip.start_file(*name, opts).unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }
    cursor.into_inner()
}

struct MockHttp {
    base_url: String,
    stop: Arc<AtomicBool>,
    handle: Option<std::thread::JoinHandle<()>>,
    #[allow(dead_code)]
    hits: Arc<std::sync::Mutex<Vec<String>>>,
}

impl MockHttp {
    fn start(routes: HashMap<String, Vec<u8>>) -> Self {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let addr = listener.local_addr().unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let stop_clone = Arc::clone(&stop);
        let hits = Arc::new(std::sync::Mutex::new(Vec::new()));
        let hits_clone = Arc::clone(&hits);

        let handle = std::thread::spawn(move || {
            while !stop_clone.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, _)) => {
                        let _ = stream.set_nonblocking(false);
                        let mut buf = [0u8; 4096];
                        let n = stream.read(&mut buf).unwrap_or(0);
                        let req = String::from_utf8_lossy(&buf[..n]);
                        let path = req
                            .lines()
                            .next()
                            .and_then(|l| l.split_whitespace().nth(1))
                            .unwrap_or("/")
                            .to_string();
                        hits_clone.lock().unwrap().push(path.clone());
                        if let Some(body) = routes.get(&path) {
                            let hdr = format!(
                                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                                body.len()
                            );
                            let _ = stream.write_all(hdr.as_bytes());
                            let _ = stream.write_all(body);
                        } else {
                            let hdr = b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                            let _ = stream.write_all(hdr);
                        }
                        let _ = stream.flush();
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(_) => break,
                }
            }
        });

        Self {
            base_url: format!("http://{addr}"),
            stop,
            handle: Some(handle),
            hits,
        }
    }
}

impl Drop for MockHttp {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(h) = self.handle.take() {
            let _ = h.join();
        }
    }
}

#[allow(dead_code)]
struct SyntheticWorld {
    server_jar: Vec<u8>,
    client_jar: Vec<u8>,
    client_lib: Vec<u8>,
    manifest_bytes: Vec<u8>,
    asset_index_bytes: Vec<u8>,
    obj_sound: Vec<u8>,
    obj_pano: Vec<u8>,
    control_png: Vec<u8>,
    lock_json: Vec<u8>,
    controls_json: Vec<u8>,
    manifest_sha1: String,
    client_sha1: String,
    server_sha1: String,
    asset_index_sha1: String,
    obj_sound_sha1: String,
    obj_pano_sha1: String,
}

fn make_synthetic_world(base_url: &str) -> SyntheticWorld {
    let block_tag = br#"{"values":["minecraft:stone"]}"#.as_slice();
    let dmg_type = br#"{"message_id":"generic","exhaustion":0.0,"scaling":"never"}"#.as_slice();
    let dmg_tag = br#"{"values":["minecraft:in_fire"]}"#.as_slice();
    let noise_json = br#"{"firstOctave":-7,"amplitudes":[1.0]}"#.as_slice();
    let struct_nbt = b"synthetic-nbt-data".as_slice();
    let dummy_json = br#"{"ok":true}"#.as_slice();

    let mut client_entries: Vec<(&str, &[u8])> = vec![
        ("version.json", br#"{"world_version":4786}"#),
        ("pack.png", b"png-icon"),
        ("assets/minecraft/textures/placeholder.png", b"jar-placeholder"),
        ("assets/minecraft/textures/gui/title/background/panorama_0.png", b"small-pano"),
        ("data/minecraft/tags/block/mineable.json", block_tag),
        ("data/minecraft/damage_type/generic.json", dmg_type),
        ("data/minecraft/tags/damage_type/is_fire.json", dmg_tag),
        ("data/minecraft/structure/village/test.nbt", struct_nbt),
        ("data/minecraft/worldgen/noise/test.json", noise_json),
        ("data/minecraft/worldgen/density_function/test.json", dummy_json),
        ("data/minecraft/worldgen/material_rule/test.json", dummy_json),
        ("data/minecraft/worldgen/material_condition/test.json", dummy_json),
        ("data/minecraft/worldgen/noise_settings/test.json", dummy_json),
        ("data/minecraft/worldgen/carver/test.json", dummy_json),
        ("data/minecraft/worldgen/biome/test.json", dummy_json),
        ("data/minecraft/worldgen/world_preset/test.json", dummy_json),
        ("data/minecraft/worldgen/feature/test.json", dummy_json),
        ("data/minecraft/worldgen/placed_feature/test.json", dummy_json),
        ("data/minecraft/worldgen/block_state_provider/test.json", dummy_json),
        ("data/minecraft/tags/worldgen/biome/test.json", dummy_json),
        ("data/minecraft/tags/worldgen/feature/test.json", dummy_json),
        ("data/minecraft/tags/fluid/water.json", dummy_json),
        ("data/minecraft/worldgen/processor_list/test.json", dummy_json),
        ("data/minecraft/worldgen/template_pool/test.json", dummy_json),
        ("data/minecraft/worldgen/structure/test.json", dummy_json),
        ("data/minecraft/worldgen/structure_set/test.json", dummy_json),
    ];
    let client_jar = build_zip(&client_entries);

    // Server inner game jar has identical data/ entries for tag & worldgen agreement
    let server_game_entries: Vec<(&str, &[u8])> = client_entries
        .drain(..)
        .filter(|(n, _)| n.starts_with("data/"))
        .collect();
    let server_game_jar = build_zip(&server_game_entries);
    let server_lib = b"synthetic-server-lib-bytes".to_vec();
    let versions_list = format!("{}\t26.3\tserver-26.3.jar\n", sha256_bytes(&server_game_jar));
    let libs_list = format!("{}\tcom.example:lib:1\tcom/example/lib/1/lib-1.jar\n", sha256_bytes(&server_lib));

    let server_jar = build_zip(&[
        ("META-INF/versions.list", versions_list.as_bytes()),
        ("META-INF/libraries.list", libs_list.as_bytes()),
        ("META-INF/versions/server-26.3.jar", &server_game_jar),
        ("META-INF/libraries/com/example/lib/1/lib-1.jar", &server_lib),
    ]);

    let client_lib = build_zip(&[("dummy.class", b"classbytes")]);
    let client_sha1 = sha1_bytes(&client_jar);
    let server_sha1 = sha1_bytes(&server_jar);
    let client_lib_sha1 = sha1_bytes(&client_lib);

    let obj_sound = br#"{"sound":{}}"#.to_vec();
    let obj_pano = b"full-real-panorama-0-bytes".to_vec();
    let obj_sound_sha1 = sha1_bytes(&obj_sound);
    let obj_pano_sha1 = sha1_bytes(&obj_pano);

    let asset_index_bytes = serde_json::to_vec(&serde_json::json!({
        "objects": {
            "minecraft/sounds.json": { "hash": obj_sound_sha1, "size": obj_sound.len() },
            "minecraft/textures/gui/title/background/panorama_0.png": { "hash": obj_pano_sha1, "size": obj_pano.len() },
        }
    }))
    .unwrap();
    let asset_index_sha1 = sha1_bytes(&asset_index_bytes);

    let manifest_bytes = serde_json::to_vec(&serde_json::json!({
        "id": "26.3",
        "javaVersion": { "majorVersion": 25 },
        "assetIndex": {
            "id": "34",
            "sha1": asset_index_sha1,
            "size": asset_index_bytes.len(),
            "url": format!("{base_url}/asset-index.json")
        },
        "downloads": {
            "client": { "sha1": client_sha1, "size": client_jar.len(), "url": format!("{base_url}/client.jar") },
            "server": { "sha1": server_sha1, "size": server_jar.len(), "url": format!("{base_url}/server.jar") },
        },
        "libraries": [
            {
                "name": "com.example:client-lib:1.0",
                "downloads": {
                    "artifact": {
                        "path": "com/example/client-lib/1.0/client-lib-1.0.jar",
                        "sha1": client_lib_sha1,
                        "size": client_lib.len(),
                        "url": format!("{base_url}/client-lib.jar")
                    }
                }
            }
        ]
    }))
    .unwrap();
    let manifest_sha1 = sha1_bytes(&manifest_bytes);

    let control_png = b"synthetic-bedrock-control-png".to_vec();
    let control_sha256 = sha256_bytes(&control_png);

    let lock_json = serde_json::to_vec_pretty(&serde_json::json!({
        "minecraft": {
            "version": "26.3",
            "manifest_sha1": manifest_sha1,
            "accepted_manifest_sha1": [],
            "accepted_asset_index_sha1": [],
            "client_sha1": client_sha1,
            "server_sha1": server_sha1,
            "java_major": 25
        }
    }))
    .unwrap();

    let controls_json = serde_json::to_vec_pretty(&serde_json::json!({
        "revision": "deadbeef1234",
        "files": [
            {
                "path": "controls/jump.png",
                "source": "gui/controls/jump.png",
                "sha256": control_sha256,
                "size": control_png.len()
            }
        ]
    }))
    .unwrap();

    SyntheticWorld {
        server_jar,
        client_jar,
        client_lib,
        manifest_bytes,
        asset_index_bytes,
        obj_sound,
        obj_pano,
        control_png,
        lock_json,
        controls_json,
        manifest_sha1,
        client_sha1,
        server_sha1,
        asset_index_sha1,
        obj_sound_sha1,
        obj_pano_sha1,
    }
}

fn routes_for(world: &SyntheticWorld, base_url: &str) -> HashMap<String, Vec<u8>> {
    let mut routes = HashMap::new();
    let catalog = serde_json::to_vec(&serde_json::json!({
        "versions": [
            {
                "id": "26.3",
                "sha1": world.manifest_sha1,
                "url": format!("{base_url}/26.3.json")
            }
        ]
    }))
    .unwrap();
    routes.insert("/catalog.json".into(), catalog);
    routes.insert("/26.3.json".into(), world.manifest_bytes.clone());
    routes.insert("/client.jar".into(), world.client_jar.clone());
    routes.insert("/server.jar".into(), world.server_jar.clone());
    routes.insert("/client-lib.jar".into(), world.client_lib.clone());
    routes.insert("/asset-index.json".into(), world.asset_index_bytes.clone());
    routes.insert(
        format!("/{}/{}", &world.obj_sound_sha1[..2], world.obj_sound_sha1),
        world.obj_sound.clone(),
    );
    routes.insert(
        format!("/{}/{}", &world.obj_pano_sha1[..2], world.obj_pano_sha1),
        world.obj_pano.clone(),
    );
    routes.insert(
        "/controls/resource_pack/textures/gui/controls/jump.png".into(),
        world.control_png.clone(),
    );
    routes
}

fn write_simulated_java_outputs(work_dir: &Path, stage_dir: &Path) {
    fs::write(
        stage_dir.join("facts.json"),
        br#"{"states":[["minecraft:air",true,false,"Block{minecraft:air}",{}],["minecraft:water",false,true,"Block{minecraft:water}",{}]]}"#,
    )
    .unwrap();
    for name in [
        "gameplay-facts.json",
        "entity-types.json",
        "block-behaviours.json",
        "creative-inventory.json",
        "model-states.json",
        "entity-models.json",
        "mob-data.json",
    ] {
        fs::write(stage_dir.join(name), b"{}").unwrap();
    }
    fs::write(stage_dir.join("worldgen/blocks/structure-blocks.json"), br#"{"rotate":[]}"#).unwrap();

    fs::write(work_dir.join("motion/movement-properties.nbt"), b"nbt").unwrap();
    fs::write(work_dir.join("motion/catalog.json"), b"{}").unwrap();
    fs::write(work_dir.join("motion/landing-definitions.json"), b"{}").unwrap();
    fs::write(work_dir.join("health/catalog.json"), b"{}").unwrap();
    fs::write(work_dir.join("health/ids.json"), b"{}").unwrap();
    fs::write(work_dir.join("collision/cached-collision.nbt"), b"nbt").unwrap();
    fs::write(work_dir.join("render/definitions.json"), b"{}").unwrap();
    fs::write(work_dir.join("render/occlusion.nbt"), b"nbt").unwrap();
    fs::write(work_dir.join("command-registries/registries.json"), b"{}").unwrap();

    let bb = work_dir.join("block-behavior");
    for f in [
        "block-properties.nbt",
        "cached-collision.nbt",
        "cached-support.nbt",
        "survival-extra-definitions.json",
        "placement-definitions.json",
    ] {
        fs::write(bb.join(f), b"{}").unwrap();
    }
    fs::write(
        bb.join("survival-classes.json"),
        br#"{"minecraft:soul_fire":"SoulFireBlock","minecraft:pumpkin_stem":"VegetationBlock"}"#,
    )
    .unwrap();
    fs::write(
        bb.join("survival-placement-classes.json"),
        br#"{"minecraft:pumpkin_stem":"StemBlock"}"#,
    )
    .unwrap();
    fs::write(
        bb.join("survival-support-tags.json"),
        br#"{"minecraft:pumpkin_stem":{"stemSupportBlocks":"minecraft:maintains_farmland"}}"#,
    )
    .unwrap();

    let reports = work_dir.join("datagen/out/reports");
    fs::create_dir_all(reports.join("minecraft/components/item")).unwrap();
    fs::create_dir_all(reports.join("biome_parameters/minecraft")).unwrap();
    fs::write(reports.join("registries.json"), b"{}").unwrap();
    fs::write(reports.join("commands.json"), b"{}").unwrap();
    fs::write(
        reports.join("minecraft/components/item/apple.json"),
        br#"{"components":{"minecraft:max_stack_size":64}}"#,
    )
    .unwrap();
    fs::write(reports.join("biome_parameters/minecraft/overworld.json"), b"{}").unwrap();
    fs::write(reports.join("biome_parameters/minecraft/nether.json"), b"{}").unwrap();
}

#[test]
fn full_phase1_and_phase2_with_mock_http_and_engine_data_zip_parity() {
    let dir = TempDir::new("full");
    let server = MockHttp::start(HashMap::new());
    let world = make_synthetic_world(&server.base_url);
    let _routes = routes_for(&world, &server.base_url);
    drop(server);

    // Re-bind doesn't guarantee same port, so construct server around a placeholder then write routes directly
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    let addr = listener.local_addr().unwrap();
    drop(listener);
    let dummy_url = format!("http://{addr}");
    let _ = dummy_url;

    // Clean approach: build two-step helper where server reads shared mutable routes map
    let shared_routes: Arc<std::sync::Mutex<HashMap<String, Vec<u8>>>> =
        Arc::new(std::sync::Mutex::new(HashMap::new()));
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let actual_base = format!("http://{}", listener.local_addr().unwrap());
    let world = make_synthetic_world(&actual_base);
    *shared_routes.lock().unwrap() = routes_for(&world, &actual_base);

    let stop = Arc::new(AtomicBool::new(false));
    let stop_c = Arc::clone(&stop);
    let routes_c = Arc::clone(&shared_routes);
    let srv_thread = std::thread::spawn(move || {
        while !stop_c.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, _)) => {
                    let _ = stream.set_nonblocking(false);
                    let mut buf = [0u8; 4096];
                    let n = stream.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let path = req
                        .lines()
                        .next()
                        .and_then(|l| l.split_whitespace().nth(1))
                        .unwrap_or("/")
                        .to_string();
                    let body_opt = routes_c.lock().unwrap().get(&path).cloned();
                    if let Some(body) = body_opt {
                        let hdr = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(hdr.as_bytes());
                        let _ = stream.write_all(&body);
                    } else {
                        let _ = stream.write_all(
                            b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                        );
                    }
                    let _ = stream.flush();
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(5));
                }
                Err(_) => break,
            }
        }
    });

    let files_dir = dir.path().join("files");
    fs::create_dir_all(&files_dir).unwrap();
    let lock_path = dir.path().join("research-lock.json");
    let controls_path = dir.path().join("bedrock-controls.json");
    fs::write(&lock_path, &world.lock_json).unwrap();
    fs::write(&controls_path, &world.controls_json).unwrap();

    let cfg = PrepareConfig {
        files_dir: files_dir.clone(),
        game_home: None,
        lock_path: lock_path.clone(),
        controls_manifest_path: controls_path.clone(),
        parity_sources_dir: None,
        catalog_url: Some(format!("{actual_base}/catalog.json")),
        objects_base_url: Some(actual_base.clone()),
        controls_base_url: Some(format!("{actual_base}/controls")),
        allow_loopback: true,
        download_workers: 4,
    };

    let cancel = CancelFlag::new();
    let p1 = run_phase1(&cfg, &cancel, &|_, _, _| {}).unwrap();
    assert_eq!(p1.controls_ready, 1);
    assert_eq!(p1.worldgen_structures_extracted, 1);
    assert_eq!(p1.worldgen_maps_written, 19);
    assert!(Path::new(&p1.java_plan.server_tasks_jsonl).is_file());
    assert!(Path::new(&p1.java_plan.client_tasks_jsonl).is_file());

    // Indexed panorama must overwrite the 1x1 JAR placeholder!
    let stage = PathBuf::from(&p1.stage_dir);
    assert_eq!(
        fs::read(stage.join("assets/minecraft/textures/gui/title/background/panorama_0.png"))
            .unwrap(),
        world.obj_pano
    );

    write_simulated_java_outputs(Path::new(&p1.work_dir), &stage);

    let p2 = run_phase2_finalize(&cfg, &cancel, &|_, _, _| {}).unwrap();
    assert!(is_prepared(&files_dir, "26.3"));
    let target = PathBuf::from(&p2.target_resources_dir);
    assert_eq!(p2.items_count, 1);
    assert_eq!(p2.survival_rules_count, 2);

    let rules: serde_json::Value =
        serde_json::from_slice(&fs::read(target.join("worldgen/blocks/survival-rules.json")).unwrap())
            .unwrap();
    assert_eq!(
        rules["minecraft:soul_fire"],
        "TagBelow:minecraft:soul_fire_base_blocks"
    );
    assert_eq!(
        rules["minecraft:pumpkin_stem"],
        "TagBelow:minecraft:maintains_farmland"
    );

    let zip_file = fs::File::open(&p2.engine_data_zip).unwrap();
    let mut archive = ZipArchive::new(zip_file).unwrap();
    assert_eq!(archive.len() as u64, p2.engine_data_entries);
    for i in 0..archive.len() {
        let entry = archive.by_index(i).unwrap();
        let entry_name = entry.name().unwrap().into_owned();
        assert!(
            is_generated_engine_file(&entry_name),
            "Unexpected entry in engine-data.zip: {}",
            entry_name
        );
    }

    let manifest: serde_json::Value =
        serde_json::from_slice(&fs::read(target.join("horizon-resources.json")).unwrap()).unwrap();
    assert_eq!(manifest["schema"], 7);
    assert!(manifest.get("output_version").is_none());
    assert_eq!(manifest["minecraft"], "26.3");
    assert!(manifest["health_files_sha256"].as_object().unwrap().len() == 4);
    assert!(manifest["motion_files_sha256"].as_object().unwrap().len() == 3);
    assert!(manifest["render_files_sha256"].as_object().unwrap().len() == 2);
    let stamp: serde_json::Value =
        serde_json::from_slice(&fs::read(target.join("horizon-prepare.json")).unwrap()).unwrap();
    assert_eq!(stamp["output_version"], 3);

    // Check package_resources_zip with bootstrap.json parity
    let bootstrap_zip = dir.path().join("with-bootstrap.zip");
    package_resources_zip(
        &target,
        &bootstrap_zip,
        true,
        Some(&serde_json::json!({"schema": 1, "version": "26.3"})),
        &cancel,
    )
    .unwrap();
    let bz = ZipArchive::new(fs::File::open(&bootstrap_zip).unwrap()).unwrap();
    assert!(bz.file_names().any(|n| n.as_deref().ok() == Some("bootstrap.json")));

    stop.store(true, Ordering::Relaxed);
    let _ = srv_thread.join();
}

#[test]
fn zl2_game_home_reuse_avoids_redundant_downloads() {
    let dir = TempDir::new("reuse");
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let world = make_synthetic_world(&base);
    let mut map = HashMap::new();
    map.insert("/server.jar".to_string(), world.server_jar.clone());
    map.insert(
        "/controls/resource_pack/textures/gui/controls/jump.png".to_string(),
        world.control_png.clone(),
    );
    let hits = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
    let hits_c = Arc::clone(&hits);
    let stop = Arc::new(AtomicBool::new(false));
    let stop_c = Arc::clone(&stop);
    let srv = std::thread::spawn(move || {
        while !stop_c.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut s, _)) => {
                    let _ = s.set_nonblocking(false);
                    let mut buf = [0u8; 4096];
                    let n = s.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let path = req
                        .lines()
                        .next()
                        .and_then(|l| l.split_whitespace().nth(1))
                        .unwrap_or("/")
                        .to_string();
                    hits_c.lock().unwrap().push(path.clone());
                    if let Some(body) = map.get(&path) {
                        let hdr = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = s.write_all(hdr.as_bytes());
                        let _ = s.write_all(body);
                    } else {
                        let _ = s.write_all(
                            b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                        );
                    }
                    let _ = s.flush();
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(5));
                }
                Err(_) => break,
            }
        }
    });

    // Populate ZL2 game_home
    let gh = dir.path().join("game_home");
    fs::create_dir_all(gh.join("versions/26.3")).unwrap();
    fs::create_dir_all(gh.join("libraries/com/example/client-lib/1.0")).unwrap();
    fs::create_dir_all(gh.join("assets/indexes")).unwrap();
    fs::create_dir_all(gh.join("assets/objects").join(&world.obj_sound_sha1[..2])).unwrap();
    fs::create_dir_all(gh.join("assets/objects").join(&world.obj_pano_sha1[..2])).unwrap();

    fs::write(gh.join("versions/26.3/26.3.json"), &world.manifest_bytes).unwrap();
    fs::write(gh.join("versions/26.3/26.3.jar"), &world.client_jar).unwrap();
    fs::write(
        gh.join("libraries/com/example/client-lib/1.0/client-lib-1.0.jar"),
        &world.client_lib,
    )
    .unwrap();
    fs::write(gh.join("assets/indexes/34.json"), &world.asset_index_bytes).unwrap();
    fs::write(
        gh.join("assets/objects")
            .join(&world.obj_sound_sha1[..2])
            .join(&world.obj_sound_sha1),
        &world.obj_sound,
    )
    .unwrap();
    fs::write(
        gh.join("assets/objects")
            .join(&world.obj_pano_sha1[..2])
            .join(&world.obj_pano_sha1),
        &world.obj_pano,
    )
    .unwrap();

    let files_dir = dir.path().join("files");
    let lock_path = dir.path().join("research-lock.json");
    let controls_path = dir.path().join("bedrock-controls.json");
    fs::write(&lock_path, &world.lock_json).unwrap();
    fs::write(&controls_path, &world.controls_json).unwrap();

    let cfg = PrepareConfig {
        files_dir,
        game_home: Some(gh),
        lock_path,
        controls_manifest_path: controls_path,
        parity_sources_dir: None,
        catalog_url: Some(format!("{base}/catalog.json")),
        objects_base_url: Some(base.clone()),
        controls_base_url: Some(format!("{base}/controls")),
        allow_loopback: true,
        download_workers: 4,
    };

    let p1 = run_phase1(&cfg, &CancelFlag::new(), &|_, _, _| {}).unwrap();
    assert!(
        p1.download_stats.files_reused >= 5,
        "Expected >= 5 reused files from ZL2 game_home, got {}",
        p1.download_stats.files_reused
    );
    let recorded_hits = hits.lock().unwrap().clone();
    assert!(
        !recorded_hits.contains(&"/client.jar".to_string()),
        "client.jar should be reused, not downloaded"
    );
    assert!(recorded_hits.contains(&"/server.jar".to_string()));

    stop.store(true, Ordering::Relaxed);
    let _ = srv.join();
}

#[test]
fn pin_mismatch_and_cancellation_clean_partials() {
    let dir = TempDir::new("pin_mismatch");
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let world = make_synthetic_world(&base);
    let mut map = routes_for(&world, &base);
    // Tamper client.jar payload so SHA-1 fails pin verification
    map.insert("/client.jar".to_string(), b"corrupt-client-jar".to_vec());

    let stop = Arc::new(AtomicBool::new(false));
    let stop_c = Arc::clone(&stop);
    let srv = std::thread::spawn(move || {
        while !stop_c.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut s, _)) => {
                    let _ = s.set_nonblocking(false);
                    let mut buf = [0u8; 4096];
                    let n = s.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let path = req
                        .lines()
                        .next()
                        .and_then(|l| l.split_whitespace().nth(1))
                        .unwrap_or("/")
                        .to_string();
                    if let Some(body) = map.get(&path) {
                        let hdr = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = s.write_all(hdr.as_bytes());
                        let _ = s.write_all(body);
                    } else {
                        let _ = s.write_all(
                            b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                        );
                    }
                    let _ = s.flush();
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(5));
                }
                Err(_) => break,
            }
        }
    });

    let files_dir = dir.path().join("files");
    let lock_path = dir.path().join("research-lock.json");
    let controls_path = dir.path().join("bedrock-controls.json");
    fs::write(&lock_path, &world.lock_json).unwrap();
    fs::write(&controls_path, &world.controls_json).unwrap();

    let cfg = PrepareConfig {
        files_dir: files_dir.clone(),
        game_home: None,
        lock_path,
        controls_manifest_path: controls_path,
        parity_sources_dir: None,
        catalog_url: Some(format!("{base}/catalog.json")),
        objects_base_url: Some(base.clone()),
        controls_base_url: Some(format!("{base}/controls")),
        allow_loopback: true,
        download_workers: 2,
    };

    let err = run_phase1(&cfg, &CancelFlag::new(), &|_, _, _| {}).unwrap_err();
    assert!(
        err.contains("mismatch") || err.contains("Checksum") || err.contains("Size"),
        "Unexpected error for pin mismatch: {err}"
    );
    assert!(!files_dir.join(".local/prepare-work/26.3/jars/client.jar.partial").exists());
    assert!(!is_prepared(&files_dir, "26.3"));

    // Cancellation path check
    let dummy_partial = files_dir.join(".local/prepare-work/26.3/test.partial");
    fs::create_dir_all(dummy_partial.parent().unwrap()).unwrap();
    fs::write(&dummy_partial, b"leftover").unwrap();
    cleanup_partials(&files_dir);
    assert!(!dummy_partial.exists());

    let cancel = CancelFlag::new();
    cancel.cancel();
    assert!(cancel.check().unwrap_err().contains("cancelled"));

    stop.store(true, Ordering::Relaxed);
    let _ = srv.join();
}

#[test]
fn synthetic_4000_file_benchmark() {
    let dir = TempDir::new("bench4k");
    let jar_path = dir.path().join("synthetic-client.jar");
    let stage = dir.path().join("stage");
    let out_zip = dir.path().join("out-engine-data.zip");

    let t_build = Instant::now();
    {
        let mut zip = ZipWriter::new(fs::File::create(&jar_path).unwrap());
        let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
        zip.start_file("version.json", opts).unwrap();
        zip.write_all(br#"{"world_version":4786}"#).unwrap();
        let payload = [0x5au8; 2048];
        for i in 0..4000u32 {
            let name = format!("assets/minecraft/textures/bench/item_{i:04}.png");
            zip.start_file(&name, opts).unwrap();
            zip.write_all(&payload).unwrap();
        }
        zip.finish().unwrap();
    }
    let build_ms = t_build.elapsed().as_millis();

    let cancel = CancelFlag::new();
    let t_extract = Instant::now();
    let (_wv, count) =
        blaze_prepare::resources::extract_client_jar_resources(&jar_path, &stage, None, &cancel)
            .unwrap();
    let extract_ms = t_extract.elapsed().as_millis();
    assert_eq!(count, 4000);

    // Second pass measures CRC32+size incremental verification
    let t_verify = Instant::now();
    let (_wv2, count2) =
        blaze_prepare::resources::extract_client_jar_resources(&jar_path, &stage, None, &cancel)
            .unwrap();
    let verify_ms = t_verify.elapsed().as_millis();
    assert_eq!(count2, 4000);

    let t_zip = Instant::now();
    let packed = package_resources_zip(&stage, &out_zip, false, None, &cancel).unwrap();
    let zip_ms = t_zip.elapsed().as_millis();
    assert_eq!(packed, 4000);

    println!(
        "BENCH_RUST_4000 build_ms={build_ms} extract_ms={extract_ms} verify_ms={verify_ms} zip_ms={zip_ms} peak_rss_kb={}",
        blaze_prepare::files::peak_rss_kb()
    );
}

fn copy_dir_recursive(src: &Path, dst: &Path) {
    fs::create_dir_all(dst).unwrap();
    for entry in fs::read_dir(src).unwrap() {
        let entry = entry.unwrap();
        let ty = entry.file_type().unwrap();
        let target = dst.join(entry.file_name());
        if ty.is_dir() {
            copy_dir_recursive(&entry.path(), &target);
        } else if ty.is_file() {
            fs::copy(entry.path(), target).unwrap();
        }
    }
}

#[test]
fn synthetic_parity_export_for_ci_oracle() {
    let dir = TempDir::new("oracle-parity");
    let shared_routes: Arc<std::sync::Mutex<HashMap<String, Vec<u8>>>> =
        Arc::new(std::sync::Mutex::new(HashMap::new()));
    let listener = TcpListener::bind("127.0.0.1:0").unwrap();
    listener.set_nonblocking(true).unwrap();
    let actual_base = format!("http://{}", listener.local_addr().unwrap());
    let world = make_synthetic_world(&actual_base);
    *shared_routes.lock().unwrap() = routes_for(&world, &actual_base);

    let stop = Arc::new(AtomicBool::new(false));
    let stop_c = Arc::clone(&stop);
    let routes_c = Arc::clone(&shared_routes);
    let srv_thread = std::thread::spawn(move || {
        while !stop_c.load(Ordering::Relaxed) {
            match listener.accept() {
                Ok((mut stream, _)) => {
                    let _ = stream.set_nonblocking(false);
                    let mut buf = [0u8; 4096];
                    let n = stream.read(&mut buf).unwrap_or(0);
                    let req = String::from_utf8_lossy(&buf[..n]);
                    let path = req
                        .lines()
                        .next()
                        .and_then(|l| l.split_whitespace().nth(1))
                        .unwrap_or("/")
                        .to_string();
                    let guard = routes_c.lock().unwrap();
                    if let Some(body) = guard.get(&path) {
                        let hdr = format!(
                            "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            body.len()
                        );
                        let _ = stream.write_all(hdr.as_bytes());
                        let _ = stream.write_all(body);
                    } else {
                        let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                    }
                }
                Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                    std::thread::sleep(std::time::Duration::from_millis(5));
                }
                Err(_) => break,
            }
        }
    });

    let files_dir = dir.path().join("files");
    fs::create_dir_all(&files_dir).unwrap();
    let lock_path = dir.path().join("research-lock.json");
    let controls_path = dir.path().join("bedrock-controls.json");
    fs::write(&lock_path, &world.lock_json).unwrap();
    fs::write(&controls_path, &world.controls_json).unwrap();

    let cfg = PrepareConfig {
        files_dir: files_dir.clone(),
        game_home: None,
        lock_path: lock_path.clone(),
        controls_manifest_path: controls_path,
        parity_sources_dir: None,
        catalog_url: Some(format!("{actual_base}/catalog.json")),
        objects_base_url: Some(actual_base.clone()),
        controls_base_url: Some(format!("{actual_base}/controls")),
        allow_loopback: true,
        download_workers: 2,
    };

    let cancel = CancelFlag::new();
    let p1 = run_phase1(&cfg, &cancel, &|_, _, _| {}).unwrap();
    let stage = PathBuf::from(&p1.stage_dir);
    let work = PathBuf::from(&p1.work_dir);
    write_simulated_java_outputs(&work, &stage);

    // Exhaustive synthetic survival inputs covering every CLASS_RULES, VEGETATION_RULES,
    // CropBlock, DoublePlantBlock, and tag-backed stem/fungus/roots branch.
    let class_names = [
        "FireBlock", "CactusBlock", "PathBlock", "MushroomBlock", "MultifaceBlock",
        "VineBlock", "HangingMossBlock", "SeaPickleBlock", "SnowLayerBlock", "BellBlock",
        "ShelfMushroomBlock", "GrowingPlantBlock", "MangrovePropaguleBlock", "SpeleothemBlock",
        "ScaffoldingBlock", "PistonHeadBlock", "ChorusPlantBlock", "ChorusFlowerBlock",
        "PitcherCropBlock", "BigDripleafBlock", "BigDripleafStemBlock", "FarmlandBlock",
        "CocoaBlock", "SugarCaneBlock", "SporeBlossomBlock", "FrogspawnBlock",
        "SmallDripleafBlock", "TallSeagrassBlock", "DoorBlock", "BaseRailBlock",
        "LeafLitterBlock", "WallSignBlock", "GrindstoneBlock", "RedstoneWallTorchBlock",
        "BubbleColumnBlock", "RedstoneWireBlock", "SoulFireBlock", "BlockBehaviour",
        "CakeBlock", "CandleCakeBlock", "BannerBlock", "StandingSignBlock",
        "WallBannerBlock", "CarpetBlock", "MossyCarpetBlock", "BaseTorchBlock",
        "CandleBlock", "LanternBlock", "HangingRootsBlock", "LadderBlock",
        "AmethystClusterBlock", "TripWireHookBlock", "BaseCoralWallFanBlock", "WallTorchBlock",
        "BasePressurePlateBlock", "DiodeBlock", "BaseCoralPlantTypeBlock",
        "CeilingHangingSignBlock", "FaceAttachedHorizontalDirectionalBlock",
        "BambooSaplingBlock", "BambooStalkBlock",
    ];
    let mut classes = serde_json::Map::new();
    let mut placement = serde_json::Map::new();
    let mut support_tags = serde_json::Map::new();
    for (idx, cls) in class_names.iter().enumerate() {
        classes.insert(format!("minecraft:cls_{idx}"), serde_json::json!(cls));
    }
    classes.insert("minecraft:synth_crop".into(), serde_json::json!("CropBlock"));
    placement.insert("minecraft:synth_crop".into(), serde_json::json!("CropBlock"));
    classes.insert("minecraft:synth_double".into(), serde_json::json!("DoublePlantBlock"));
    placement.insert("minecraft:synth_double".into(), serde_json::json!("VegetationBlock"));
    for (idx, pcls) in [
        "LilyPadBlock", "VegetationBlock", "AzaleaBlock", "DryVegetationBlock",
        "NetherSproutsBlock", "NetherWartBlock", "WitherRoseBlock", "CactusFlowerBlock",
        "SeagrassBlock",
    ]
    .iter()
    .enumerate()
    {
        let k = format!("minecraft:veg_{idx}");
        classes.insert(k.clone(), serde_json::json!("VegetationBlock"));
        placement.insert(k, serde_json::json!(pcls));
    }
    for (name, pcls, field, tag) in [
        ("minecraft:synth_stem", "StemBlock", "stemSupportBlocks", "minecraft:maintains_farmland"),
        ("minecraft:synth_attached_stem", "AttachedStemBlock", "supportBlocks", "minecraft:farmland"),
        ("minecraft:synth_fungus", "NetherFungusBlock", "supportBlocks", "minecraft:fungus_support"),
        ("minecraft:synth_roots", "NetherRootsBlock", "supportBlocks", "minecraft:roots_support"),
    ] {
        classes.insert(name.into(), serde_json::json!("VegetationBlock"));
        placement.insert(name.into(), serde_json::json!(pcls));
        support_tags.insert(name.into(), serde_json::json!({ field: tag }));
    }

    let bb = work.join("block-behavior");
    let classes_bytes = serde_json::to_vec_pretty(&classes).unwrap();
    let placement_bytes = serde_json::to_vec_pretty(&placement).unwrap();
    let support_bytes = serde_json::to_vec_pretty(&support_tags).unwrap();
    fs::write(bb.join("survival-classes.json"), &classes_bytes).unwrap();
    fs::write(bb.join("survival-placement-classes.json"), &placement_bytes).unwrap();
    fs::write(bb.join("survival-support-tags.json"), &support_bytes).unwrap();

    let p2 = run_phase2_finalize(&cfg, &cancel, &|_, _, _| {}).unwrap();
    let expected_rules = (class_names.len() + 1 + 1 + 9 + 4) as u64;
    assert_eq!(p2.survival_rules_count, expected_rules);
    let target = PathBuf::from(&p2.target_resources_dir);
    let packaged_all_zip = dir.path().join("packaged-all.zip");
    package_resources_zip(&target, &packaged_all_zip, false, None, &cancel).unwrap();
    let packaged_bs_zip = dir.path().join("packaged-with-bootstrap.zip");
    package_resources_zip(
        &target,
        &packaged_bs_zip,
        false,
        Some(&serde_json::json!({"schema": 1, "version": "26.3"})),
        &cancel,
    )
    .unwrap();

    if let Some(dump_dir) = std::env::var_os("BLAZE_ORACLE_DUMP_DIR") {
        let dump = PathBuf::from(dump_dir);
        let inputs = dump.join("inputs");
        let outputs = dump.join("outputs");
        fs::create_dir_all(&inputs).unwrap();
        fs::create_dir_all(&outputs).unwrap();
        fs::write(inputs.join("survival-classes.json"), &classes_bytes).unwrap();
        fs::write(inputs.join("survival-placement-classes.json"), &placement_bytes).unwrap();
        fs::write(inputs.join("survival-support-tags.json"), &support_bytes).unwrap();
        fs::write(inputs.join("server.jar"), &world.server_jar).unwrap();
        fs::write(inputs.join("research-lock.json"), &world.lock_json).unwrap();
        copy_dir_recursive(&target, &outputs.join("resources"));
        fs::create_dir_all(outputs.join("unpacked-server")).unwrap();
        fs::copy(
            work.join("jars/server-game.jar"),
            outputs.join("unpacked-server/server-game.jar"),
        )
        .unwrap();
        copy_dir_recursive(
            &work.join("libraries/server"),
            &outputs.join("unpacked-server/libraries"),
        );
        fs::copy(&p2.engine_data_zip, outputs.join("engine-data.zip")).unwrap();
        fs::copy(&packaged_all_zip, outputs.join("packaged-all.zip")).unwrap();
        fs::copy(&packaged_bs_zip, outputs.join("packaged-with-bootstrap.zip")).unwrap();
    }

    stop.store(true, Ordering::Relaxed);
    let _ = srv_thread.join();
}
