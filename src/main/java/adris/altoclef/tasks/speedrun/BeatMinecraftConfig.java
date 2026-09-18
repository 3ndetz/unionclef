package adris.altoclef.tasks.speedrun;

public class BeatMinecraftConfig {
    public int targetEyes = 14; // how many eyes of ender to collect
    public int minimumEyes = 12; // the MINIMUM amount of eyes of ender to have, assuming we don't have our stronghold portal opened yet
    public boolean placeSpawnNearEndPortal = true;
    public boolean barterPearlsInsteadOfEndermanHunt;
    public boolean sleepThroughNight = true;
    public boolean rePickupCraftingTable = true;
    public boolean searchRuinedPortals = true;
    public boolean searchDesertTemples = true;
    // G106 (2026-09-18): 220 units (~27 cooked meats) is a whole-journey stockpile, and gathering
    // it BEFORE the nether -- food gates the "Going to Nether" fall-through (BeatMinecraftTask:2412
    // needs every gather <=0) -- is what kept the day-locked probe in overworld prep for 25 min
    // without leaving. The bot re-collects whenever foodPotential drops below foodUnits, and
    // needsEmergencyFood ramps to +inf when actually low, so a smaller buffer is topped up rather
    // than risked. 140 (~17 meats) still carries a nether trip with margin; it proceeds sooner.
    public int minFoodUnits = 120;
    public int foodUnits = 140;
    public int requiredBeds = 10;
    public boolean alwaysCookRawFood = true;
    public int minBuildMaterialCount = 5;
    public int buildMaterialCount = 64;
    public double dragonHeadCloseEnoughClickBedRange = 5.3;
    public boolean ironGearBeforeDiamondGear = true;
    public boolean getShield = true;
    public boolean renderDistanceManipulation = true;
    public boolean rePickupSmoker = true;
    public boolean rePickupFurnace = true;
}
