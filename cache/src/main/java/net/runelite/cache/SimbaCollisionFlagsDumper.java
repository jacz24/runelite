/*
 * Dumps per-tile wall/door/corner EDGE flags from the OSRS cache (8-connectivity).
 *
 * The rendered collision.zip flattens walls/doors into 1px edge lines that are
 * not watertight, so flood-fill cannot cleanly partition rooms from it. This
 * dumper records the same wall/door data SimbaCollisionMapDumper.drawObjects
 * extracts, but as exact tile-edge flags -- and additionally captures the
 * diagonal/corner wall loc types the PNG drops (type 1) or can only approximate
 * (type 3 single corner, type 9 diagonal), so region-mapper can run a faithful
 * 8-directional flood.
 *
 * Output: collision_flags.zip, one entry "{plane}/{regionX}-{regionY}.bin" per
 * region+plane that has any flag. Each entry is Region.X*Region.Y*2 = 8192 bytes,
 * TWO bytes per tile, index = (ty*64 + tx)*2 with tx = localX and ty = 63 - localY
 * (matching the collision PNG chunk orientation so region-mapper indexing is
 * unchanged).
 *
 *   byte 0  wall block (8 dir, mirrors CollisionDataFlag low byte):
 *     NW 0x01  N 0x02  NE 0x04  E 0x08  SE 0x10  S 0x20  SW 0x40  W 0x80
 *   byte 1  door + meta:
 *     doorN 0x01  doorE 0x02  doorS 0x04  doorW 0x08   FULL(non-standable) 0x10
 *
 * Loc -> flags (set only on the loc's own tile; region-mapper ORs both tiles'
 * facing bits, so no neighbour mirror is needed):
 *   type 0:    one cardinal edge (wall WALL_EDGE{W,N,E,S} / door DOOR_EDGE{N,E,S,W} when functional)
 *   type 2:    the type-0 edge PLUS a second edge CORNER2_EDGE{N,E,S,W}
 *   type 1,3:  one corner bit CORNER_EDGE{NW,NE,SE,SW}  (type 1 is the gap the PNG drops)
 *   type 9:    FULL (byte1 0x10) -> tile non-standable; 8-conn rule seals diagonal fences
 *
 * Edge mapping for types 0/2 is verified against SimbaCollisionMapDumper pixel
 * draws (overlay-confirmed). Corner/diagonal semantics are per CollisionDataFlag
 * + WorldArea.canTravelInDirection (runelite-api).
 */
package net.runelite.cache;

import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.*;
import net.runelite.cache.fs.*;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.KeyProvider;
import net.runelite.cache.util.XteaKeyManager;
import org.apache.commons.cli.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
public class SimbaCollisionFlagsDumper
{
	// cardinal indices: 0=N 1=E 2=S 3=W ; corner indices: 0=NW 1=NE 2=SE 3=SW
	private static final int CN = 0, CE = 1, CS = 2, CW = 3;

	// byte 0 (wall) bit per cardinal index {N,E,S,W}
	private static final int[] CARD_WALL = {0x02, 0x08, 0x20, 0x80};
	// byte 0 (wall) bit per corner index {NW,NE,SE,SW}
	private static final int[] CORNER_WALL = {0x01, 0x04, 0x10, 0x40};
	// byte 1 (door) bit per cardinal index {N,E,S,W}
	private static final int[] CARD_DOOR = {0x01, 0x02, 0x04, 0x08};
	// byte 1 meta
	private static final int FULL = 0x10;

	// rotation -> edge/corner index (see header; verified vs drawObjects)
	private static final int[] WALL_EDGE = {CW, CN, CE, CS};    // type 0/2 primary, non-functional
	private static final int[] DOOR_EDGE = {CN, CE, CS, CW};    // type 0/2 functional door
	private static final int[] CORNER2_EDGE = {CN, CE, CS, CW}; // type 2 second edge
	private static final int[] CORNER_EDGE = {0, 1, 2, 3};      // type 1/3: rot -> {NW,NE,SE,SW}

	private final RegionLoader regionLoader;
	private final ObjectManager objectManager;

	public SimbaCollisionFlagsDumper(Store store, KeyProvider keyProvider)
	{
		this.regionLoader = new RegionLoader(store, keyProvider);
		this.objectManager = new ObjectManager(store);
	}

	public static void main(String[] args) throws IOException
	{
		long start = System.currentTimeMillis();
		Options options = new Options();
		options.addOption(Option.builder().longOpt("cachedir").hasArg().required().build());
		options.addOption(Option.builder().longOpt("cachename").hasArg().required().build());
		options.addOption(Option.builder().longOpt("outputdir").hasArg().required().build());

		CommandLine cmd;
		try
		{
			cmd = new DefaultParser().parse(options, args);
		}
		catch (ParseException ex)
		{
			System.err.println("Error parsing command line options: " + ex.getMessage());
			System.exit(-1);
			return;
		}

		String mainDir = cmd.getOptionValue("cachedir");
		String cacheName = cmd.getOptionValue("cachename");
		String cacheDirectory = mainDir + File.separator + cacheName + File.separator + "cache";
		String xteaJSONPath = mainDir + File.separator + cacheName + File.separator + cacheName.replace("cache-", "keys-") + ".json";
		String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName;

		XteaKeyManager xtea = new XteaKeyManager();
		if (Files.exists(Path.of(xteaJSONPath)))
		{
			try (FileInputStream fin = new FileInputStream(xteaJSONPath))
			{
				xtea.loadKeys(fin);
			}
		}

		File outDir = new File(outputDirectory);
		if (!outDir.exists() && !outDir.mkdirs()) throw new RuntimeException("Failed to create output path: " + outDir.getPath());

		try (Store store = new Store(new File(cacheDirectory)))
		{
			store.load();
			SimbaCollisionFlagsDumper dumper = new SimbaCollisionFlagsDumper(store, xtea);
			dumper.load();

			int written;
			try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(
				new FileOutputStream(new File(outputDirectory, "collision_flags.zip")))))
			{
				written = dumper.dump(zip);
			}
			System.out.println("SimbaCollisionFlagsDumper wrote " + written + " region-planes in "
				+ (System.currentTimeMillis() - start) + " ms");
		}
	}

	public SimbaCollisionFlagsDumper load() throws IOException
	{
		objectManager.load();
		regionLoader.loadRegions();
		regionLoader.calculateBounds();
		return this;
	}

	private int dump(ZipOutputStream zip) throws IOException
	{
		int written = 0;
		int regions = 0;
		int examined = 0;
		for (Region region : regionLoader.getRegions())
		{
			regions++;
			for (int z = 0; z < Region.Z; z++)
			{
				examined++;
				byte[] flags = new byte[Region.X * Region.Y * 2];
				if (!computeRegionPlane(region, z, flags)) continue;
				zip.putNextEntry(new ZipEntry(z + "/" + region.getRegionX() + "-" + region.getRegionY() + ".bin"));
				zip.write(flags);
				zip.closeEntry();
				written++;
			}
		}
		// Coverage denominator, printed so the written count can be reconciled
		// rather than assumed: a region-plane is skipped ONLY when no loc in it
		// set a single flag bit. skipped = examined - written.
		System.out.println("SimbaCollisionFlagsDumper coverage: regions=" + regions
			+ " planes_examined=" + examined
			+ " written=" + written
			+ " skipped_flagless=" + (examined - written));
		return written;
	}

	// Returns true if any flag bit was set for this region+plane.
	private boolean computeRegionPlane(Region region, int z, byte[] flags)
	{
		boolean any = false;
		for (Location loc : region.getLocations())
		{
			Position pos = loc.getPosition();
			int localX = pos.getX() - region.getBaseX();
			int localY = pos.getY() - region.getBaseY();
			if (localX < 0 || localX >= Region.X || localY < 0 || localY >= Region.Y) continue;

			boolean isBridge = (region.getTileSetting(1, localX, localY) & 2) != 0;
			int tileZ = z + (isBridge ? 1 : 0);
			if (pos.getZ() != tileZ) continue;
			if ((region.getTileSetting(z, localX, localY) & 24) != 0) continue;

			if (applyLocation(loc, localX, localY, flags)) any = true;
		}
		return any;
	}

	// Mirrors SimbaCollisionMapDumper.drawObjects wall/door classification for types 0/2,
	// and adds corner (1/3) + diagonal (9) loc types the PNG drops.
	private boolean applyLocation(Location loc, int localX, int localY, byte[] flags)
	{
		int type = loc.getType();
		boolean wallish = (type >= 0 && type <= 3) || type == 9;
		if (!wallish) return false;

		ObjectDefinition object = objectManager.getObject(loc.getId());
		if (object == null || object.getInteractType() == 0) return false;
		if (object.getId() == 24720) return false;          // wintertodt invisible non-collision walls

		boolean isDoor = object.getWallOrDoor() != 0;
		int rotation = loc.getOrientation();
		if (rotation < 0 || rotation > 3) return false;

		int binTx = localX;
		int binTy = Region.Y - object.getSizeY() - localY;
		if (binTx < 0 || binTx >= Region.X || binTy < 0 || binTy >= Region.Y) return false;

		if (type == 9)                                      // diagonal wall -> seal the whole tile
		{
			flags[(binTy * Region.X + binTx) * 2 + 1] |= (byte) FULL;
			return true;
		}

		if (type == 1 || type == 3)                         // corner connector -> single corner bit
		{
			setWall(flags, binTx, binTy, CORNER_WALL[CORNER_EDGE[rotation]]);
			return true;
		}

		// type 0 or 2: primary cardinal edge (verbatim verified behaviour)
		EntityOpsDefinition.Op op = new EntityOpsDefinition.Op("Close");
		boolean curtain = object.getName() != null && object.getName().contains("urtain");
		boolean functional = object.getWallOrDoor() != 0 && !curtain
			&& object.getOps() != null && object.getOps().getOps().contains(op);
		int card = functional ? DOOR_EDGE[rotation] : WALL_EDGE[rotation];
		setCardinal(flags, binTx, binTy, card, isDoor);

		if (type == 2)                                      // wall corner blocks a second, perpendicular edge
			setCardinal(flags, binTx, binTy, CORNER2_EDGE[rotation], isDoor);

		return true;
	}

	private void setCardinal(byte[] flags, int tx, int ty, int card, boolean isDoor)
	{
		int base = (ty * Region.X + tx) * 2;
		if (isDoor) flags[base + 1] |= (byte) CARD_DOOR[card];
		else flags[base] |= (byte) CARD_WALL[card];
	}

	private void setWall(byte[] flags, int tx, int ty, int bit0)
	{
		flags[(ty * Region.X + tx) * 2] |= (byte) bit0;
	}
}
