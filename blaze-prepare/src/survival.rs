// Blaze Launcher — on-device 26.3 engine-data preparer
// Copyright (C) 2026 Blaze Launcher contributors
// SPDX-License-Identifier: GPL-3.0-or-later

//! Survival placement rule table generator ported from Starling's `horizon-prepare/src/survival.rs`.

use serde_json::{Map, Value};

const CLASS_RULES: &[(&str, &str)] = &[
    ("FireBlock", "Fire"),
    ("CactusBlock", "Cactus"),
    ("PathBlock", "Path"),
    ("MushroomBlock", "Mushroom"),
    ("MultifaceBlock", "Multiface"),
    ("VineBlock", "Vine"),
    ("HangingMossBlock", "HangingMoss"),
    ("SeaPickleBlock", "SeaPickle"),
    ("SnowLayerBlock", "SnowLayer"),
    ("BellBlock", "Bell"),
    ("ShelfMushroomBlock", "FullBehind"),
    ("GrowingPlantBlock", "GrowingPlant"),
    ("MangrovePropaguleBlock", "MangrovePropagule"),
    ("SpeleothemBlock", "Speleothem"),
    ("ScaffoldingBlock", "Scaffolding"),
    ("PistonHeadBlock", "PistonHead"),
    ("ChorusPlantBlock", "ChorusPlant"),
    ("ChorusFlowerBlock", "ChorusFlower"),
    ("PitcherCropBlock", "PitcherCrop"),
    ("BigDripleafBlock", "BigDripleaf"),
    ("BigDripleafStemBlock", "BigDripleafStem"),
    ("FarmlandBlock", "Farmland"),
    ("CocoaBlock", "Cocoa"),
    ("SugarCaneBlock", "SugarCane"),
    ("SporeBlossomBlock", "SporeBlossom"),
    ("FrogspawnBlock", "Frogspawn"),
    ("SmallDripleafBlock", "SmallDripleaf"),
    ("TallSeagrassBlock", "TallSeagrass"),
    ("DoorBlock", "Door"),
    ("BaseRailBlock", "RigidBelow"),
    ("LeafLitterBlock", "FullBelow"),
    ("WallSignBlock", "SolidBehind"),
    ("GrindstoneBlock", "Always"),
    ("RedstoneWallTorchBlock", "FullBehind"),
    ("BubbleColumnBlock", "BubbleColumn"),
    ("RedstoneWireBlock", "RedstoneWire"),
    ("SoulFireBlock", "TagBelow:minecraft:soul_fire_base_blocks"),
    ("BlockBehaviour", "Always"),
    ("CakeBlock", "SolidBelow"),
    ("CandleCakeBlock", "SolidBelow"),
    ("BannerBlock", "SolidBelow"),
    ("StandingSignBlock", "SolidBelow"),
    ("WallBannerBlock", "SolidBehind"),
    ("CarpetBlock", "NonAirBelow"),
    ("MossyCarpetBlock", "MossCarpet"),
    ("BaseTorchBlock", "CenterBelow"),
    ("CandleBlock", "CenterBelow"),
    ("LanternBlock", "Lantern"),
    ("HangingRootsBlock", "FullAbove"),
    ("LadderBlock", "FullBehind"),
    ("AmethystClusterBlock", "FullBehind"),
    ("TripWireHookBlock", "FullBehind"),
    ("BaseCoralWallFanBlock", "FullBehind"),
    ("WallTorchBlock", "FullBehind"),
    ("BasePressurePlateBlock", "RigidOrCenterBelow"),
    ("DiodeBlock", "RigidBelow"),
    ("BaseCoralPlantTypeBlock", "FullBelow"),
    ("CeilingHangingSignBlock", "CenterAbove"),
    ("FaceAttachedHorizontalDirectionalBlock", "FaceAttached"),
    ("BambooSaplingBlock", "SupportsBamboo"),
    ("BambooStalkBlock", "SupportsBamboo"),
];

const VEGETATION_RULES: &[(&str, &str)] = &[
    ("LilyPadBlock", "LilyPad"),
    ("VegetationBlock", "TagBelow:minecraft:supports_vegetation"),
    ("AzaleaBlock", "TagBelow:minecraft:supports_azalea"),
    (
        "DryVegetationBlock",
        "TagBelow:minecraft:supports_dry_vegetation",
    ),
    (
        "NetherSproutsBlock",
        "TagBelow:minecraft:supports_nether_sprouts",
    ),
    ("NetherWartBlock", "TagBelow:minecraft:supports_nether_wart"),
    ("WitherRoseBlock", "TagBelow:minecraft:supports_wither_rose"),
    ("CactusFlowerBlock", "CactusFlower"),
    ("SeagrassBlock", "Seagrass"),
];

fn lookup(table: &[(&str, &'static str)], key: &str) -> Option<&'static str> {
    table.iter().find(|(k, _)| *k == key).map(|(_, v)| *v)
}

/// Builds `{block: rule}` from the VanillaSimpleBlockOracle outputs.
pub fn bind_definitions(
    classes: &Value,
    placement: &Value,
    support_tags: &Value,
) -> Result<Value, String> {
    let classes = classes
        .as_object()
        .ok_or("survival-classes.json is not a JSON object")?;
    let mut definitions = Map::new();
    for (name, owner) in classes {
        if let Some(rule) = owner.as_str().and_then(|o| lookup(CLASS_RULES, o)) {
            definitions.insert(name.clone(), Value::String(rule.to_string()));
        }
    }
    for (name, owner) in classes {
        let owner = owner.as_str().unwrap_or("");
        let placed = placement.get(name).and_then(Value::as_str);
        if owner == "CropBlock" && placed == Some("CropBlock") {
            definitions.insert(name.clone(), Value::String("Crop".to_string()));
        }
        if owner == "DoublePlantBlock" && placed == Some("VegetationBlock") {
            definitions.insert(name.clone(), Value::String("DoublePlant".to_string()));
        }
        if owner != "VegetationBlock" {
            continue;
        }
        let method = placed.ok_or_else(|| format!("No placement class for {name}"))?;
        if let Some(rule) = lookup(VEGETATION_RULES, method) {
            definitions.insert(name.clone(), Value::String(rule.to_string()));
        } else if [
            "AttachedStemBlock",
            "StemBlock",
            "NetherFungusBlock",
            "NetherRootsBlock",
        ]
        .contains(&method)
        {
            let field = if method == "StemBlock" {
                "stemSupportBlocks"
            } else {
                "supportBlocks"
            };
            let tag = support_tags
                .get(name)
                .and_then(|t| t.get(field))
                .and_then(Value::as_str)
                .ok_or_else(|| format!("No {field} support tag for {name}"))?;
            definitions.insert(name.clone(), Value::String(format!("TagBelow:{tag}")));
        }
    }
    Ok(Value::Object(definitions))
}
