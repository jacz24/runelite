/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.cache;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.*;
import net.runelite.cache.fs.*;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Position;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import net.runelite.cache.util.BigBufferedImage;
import net.runelite.cache.util.KeyProvider;
import net.runelite.cache.util.XteaKeyManager;
import org.apache.commons.cli.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Slf4j
@Accessors(chain = true)
public class SimbaCollisionMapDumper
{
	private static final int MAP_SCALE = 4; // this squared is the number of pixels per map square

	private static final int collisionColor = 0xFF333333;
	private static final int wallColor = 0xFF000000;
	private static final int doorColor = 0xFFFF0000;
	private static final int walkableColor = 0xFFFFFFFF;

	private final RegionLoader regionLoader;
	private final AreaManager areas;
	private final ObjectManager objectManager;
	public static boolean exportFullMap = false;
	private static boolean exportChunks = true;
	private static final boolean exportEmptyImages = true;

	private static int x1 = -1;
	private static int y1 = -1;
	private static int x2 = -1;
	private static int y2 = -1;

	private static int mainlandX1 = 15;
	private static int mainlandY1 = 32;
	private static int mainlandX2 = 62;
	private static int mainlandY2 = 65;


	@Getter
	@Setter
	private boolean renderMap = true;

	@Getter
	@Setter
	private boolean renderObjects = true;

	@Getter
	@Setter
	private boolean transparency = false;

	@Getter
	@Setter
	private boolean lowMemory = false;

	public SimbaCollisionMapDumper(Store store, KeyProvider keyProvider)
	{
		this(store, new RegionLoader(store, keyProvider));
	}

	public SimbaCollisionMapDumper(Store store, RegionLoader regionLoader)
	{
		this.regionLoader = regionLoader;
		this.areas = new AreaManager(store);
		this.objectManager = new ObjectManager(store);
	}

	private static final BufferedImage BLACK_CHUNK =
			new BufferedImage(
					Region.X * MAP_SCALE,
					Region.Y * MAP_SCALE,
					BufferedImage.TYPE_INT_RGB
			);

	public static void main(String[] args) throws IOException
	{
		long start = System.currentTimeMillis();
		Options options = new Options();
		options.addOption(Option.builder().longOpt("cachedir").hasArg().required().build());
		options.addOption(Option.builder().longOpt("cachename").hasArg().required().build());
		options.addOption(Option.builder().longOpt("outputdir").hasArg().required().build());

		CommandLineParser parser = new DefaultParser();
		CommandLine cmd;
		try
		{
			cmd = parser.parse(options, args);
		}
		catch (ParseException ex)
		{
			System.err.println("Error parsing command line options: " + ex.getMessage());
			System.exit(-1);
			return;
		}

		final String mainDir = cmd.getOptionValue("cachedir");
		final String cacheName = cmd.getOptionValue("cachename");

		final String cacheDirectory = mainDir + File.separator + cacheName + File.separator + "cache";

		final String xteaJSONPath = mainDir + File.separator + cacheName + File.separator + cacheName.replace("cache-", "keys-") + ".json";
		final String outputDirectory = cmd.getOptionValue("outputdir") + File.separator + cacheName;
		final String outputDirectoryEx = outputDirectory + File.separator + "collision";

		XteaKeyManager xteaKeyManager = new XteaKeyManager();
		if (Files.exists(Path.of(xteaJSONPath)))
		{
			try (FileInputStream fin = new FileInputStream(xteaJSONPath))
			{
				xteaKeyManager.loadKeys(fin);
			}
		}

		File base = new File(cacheDirectory);
		File outDir;

		if (exportFullMap) outDir = new File(outputDirectoryEx);
		else outDir = new File(outputDirectory);

		if (!outDir.exists() && !outDir.mkdirs()) throw new RuntimeException("Failed to create output path: " + outDir.getPath());
		if (!exportFullMap) exportChunks = true;

		try (Store store = new Store(base))
		{
			store.load();
			SimbaCollisionMapDumper dumper = new SimbaCollisionMapDumper(store, xteaKeyManager);
			dumper.load();

			ZipOutputStream zip = null;
			if (exportChunks) {
				zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(outputDirectory, "collision.zip"))));
			}

			for (int i = 0; i < Region.Z; ++i)
			{
				BufferedImage image = dumper.drawRegions(i, zip);
				if (exportFullMap) {
					File imageFile = new File(outDir, "img-" + i + ".png");
					ImageIO.write(image, "png", imageFile);
					log.info("Wrote image {}", imageFile);
				}
			}

			if (zip != null) zip.close();
		}

		long end = System.currentTimeMillis();
		System.out.println("SimbaCollisionMapDumper took " + (end - start) + " ms");
	}

	public SimbaCollisionMapDumper load() throws IOException
	{
		objectManager.load();

		loadRegions();
		areas.load();
		return this;
	}

	public BufferedImage drawRegions(int z, ZipOutputStream zip) throws IOException {
		int minX = regionLoader.getLowestX().getBaseX();
		int minY = regionLoader.getLowestY().getBaseY();

		int maxX = regionLoader.getHighestX().getBaseX() + Region.X;
		int maxY = regionLoader.getHighestY().getBaseY() + Region.Y;

		int dimX = maxX - minX;
		int dimY = maxY - minY;

		int pixelsX = dimX * MAP_SCALE;
		int pixelsY = dimY * MAP_SCALE;

		log.info("Map image dimensions: {}px x {}px, {}px per map square ({} MB). Max memory: {}mb", pixelsX, pixelsY,
			MAP_SCALE, (pixelsX * pixelsY * 3 / 1024 / 1024),
			Runtime.getRuntime().maxMemory() / 1024L / 1024L);

		BufferedImage image;
		if (lowMemory)
			image = BigBufferedImage.create(pixelsX, pixelsY, transparency ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
		else
			image = new BufferedImage(pixelsX, pixelsY, transparency ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);

		drawRegions(image, z, zip);

		return image;
	}

	public static boolean isImageEmpty(BufferedImage img) {
		if (exportEmptyImages) return false;

		int width = img.getWidth();
		int height = img.getHeight();

		int color = img.getRGB(0,0);
		for (int y = 1; y < height; y++)
			for (int x = 0; x < width; x++)
				if (img.getRGB(x, y) != color)
					return false;
		return true;
	}

	private static boolean isFullyWhite(BufferedImage img) {
		int width = img.getWidth();
		int height = img.getHeight();

		for (int y = 0; y < height; y++)
			for (int x = 0; x < width; x++)
				if (img.getRGB(x, y) != 0xFFFFFFFF)
					return false;

		return true;
	}

	private void drawRegions(BufferedImage image, int z, ZipOutputStream zip) throws IOException {
		for (Region region : regionLoader.getRegions())
		{
			if (x1 != -1 &&  x2 != -1 && y1 != -1 && y2 != -1)
			{
				if (region.getRegionX() < x1) continue;
				if (region.getRegionX() > x2) continue;
				if (region.getRegionY() < y1) continue;
				if (region.getRegionY() > y2) continue;
			}

			int baseX = region.getBaseX();
			int baseY = region.getBaseY();

			// to pixel X
			int drawBaseX = baseX - regionLoader.getLowestX().getBaseX();

			// to pixel Y. top most y is 0, but the top most
			// region has the greatest y, so invert
			int drawBaseY = regionLoader.getHighestY().getBaseY() - baseY;

			drawRegions(image, drawBaseX, drawBaseY, z, region);
			drawObjects(image, drawBaseX, drawBaseY, region, z);

			if (exportChunks) {
				BufferedImage chunk = image.getSubimage(drawBaseX * MAP_SCALE, drawBaseY * MAP_SCALE, Region.X * MAP_SCALE, Region.Y * MAP_SCALE);
				if (!isImageEmpty(chunk)) {

					boolean insideMainland = (z == 0) && region.getRegionX() >= mainlandX1 &&
						region.getRegionX() <= mainlandX2 &&
						region.getRegionY() >= mainlandY1 &&
						region.getRegionY() <= mainlandY2;

					if (!insideMainland && isFullyWhite(chunk)) {
						chunk = BLACK_CHUNK;
					}
					zip.putNextEntry(new ZipEntry(z + "-" + region.getRegionX() + "-" + region.getRegionY() + ".png"));
					ImageIO.write(chunk, "png", zip);
				}
			}
		}
	}

	private void drawRegions(BufferedImage image, int drawBaseX, int drawBaseY, int z, Region region)
	{
		if (!renderMap) return;

		for (int x = 0; x < Region.X; ++x)
		{
			for (int y = 0; y < Region.Y; ++y)
			{
				int drawY = Region.Y - y - 1;
				int tileSetting = region.getTileSetting(z, x, Region.Y - y - 1);

				if ((tileSetting & 24) != 0) {
					drawTile(image, (tileSetting & 1) == 0, drawBaseX, drawBaseY, z, x, y);
					continue;
				};

				if (z == 0) {
					boolean hasBridge = (region.getTileSetting(1, x, drawY) & 2) != 0;
					if (hasBridge) {
						int upperSetting = region.getTileSetting(1, x, drawY);
                        drawTile(image, (upperSetting & 1) == 0, drawBaseX, drawBaseY, 0, x, y);
						continue;
					}
				}

				drawTile(image, (tileSetting & 1) == 0, drawBaseX, drawBaseY, z, x, y);
			}
		}
	}

	private void drawTile(BufferedImage img, boolean walkable, int drawBaseX, int drawBaseY, int z, int x, int y)
	{
		if (walkable) {
			for (int i = 0; i < MAP_SCALE; ++i)
			{
				for (int j = 0; j < MAP_SCALE; ++j)
				{
					int tempX = drawBaseX * MAP_SCALE + x * MAP_SCALE + i;
					int tempY = drawBaseY * MAP_SCALE + y * MAP_SCALE + j;
					img.setRGB(tempX,tempY, walkableColor);
				}
			}
			return;
		}

		for (int i = 0; i < MAP_SCALE; ++i)
		{
			for (int j = 0; j < MAP_SCALE; ++j)
			{
				int tempX = drawBaseX * MAP_SCALE + x * MAP_SCALE + i;
				int tempY = drawBaseY * MAP_SCALE + y * MAP_SCALE + j;

				img.setRGB(tempX, tempY, wallColor);
			}
		}
	}

	private void drawObjects(BufferedImage image, int drawBaseX, int drawBaseY, Region region, int z)
	{
		if (!renderObjects) return;

		List<Location> planeLocs = new ArrayList<>();
		List<Location> pushDownLocs = new ArrayList<>();
		List<List<Location>> layers = Arrays.asList(planeLocs, pushDownLocs);

		for (int localX = 0; localX < Region.X; localX++)
		{
			int regionX = localX + region.getBaseX();
			for (int localY = 0; localY < Region.Y; localY++)
			{
				int regionY = localY + region.getBaseY();

				planeLocs.clear();
				pushDownLocs.clear();
				boolean isBridge = (region.getTileSetting(1, localX, localY) & 2) != 0;

				int tileZ = z + (isBridge ? 1 : 0);

				for (Location loc : region.getLocations())
				{
					Position pos = loc.getPosition();
					if (pos.getX() != regionX || pos.getY() != regionY) continue;

					if (pos.getZ() == tileZ && (region.getTileSetting(z, localX, localY) & 24) == 0)
						planeLocs.add(loc);
				}

				for (List<Location> locs : layers)
				{
					for (Location location : locs)
					{
						int type = location.getType();
						if (type >= 4 && type <= 8){
							continue;
						}

						ObjectDefinition object = findObject(location.getId());
						if (object.getInteractType() == 0) continue;

						//0	- straight walls, fences etc
						//1	- diagonal walls corner, fences etc connectors
						//2	- entire walls, fences etc corners
						//3	- straight wall corners, fences etc connectors
						//4	- straight inside wall decoration
						//5	- straight outside wall decoration
						//6	- diagonal outside wall decoration
						//7	- diagonal inside wall decoration
						//8	- diagonal in wall decoration
						//9	- diagonal walls, fences etc
						//10 - all kinds of objects, trees, statues, signs, fountains etc etc
						//11 - ground objects like daisies etc
						//12 - straight sloped roofs
						//13 - diagonal sloped roofs
						//14 - diagonal slope connecting roofs
						//15 - straight sloped corner connecting roofs
						//16 - straight sloped corner roof
						//17 - straight flat top roofs
						//18 - straight bottom egde roofs
						//19 - diagonal bottom edge connecting roofs
						//20 - straight bottom edge connecting roofs
						//21 - straight bottom edge connecting corner roofs
						//22 - ground decoration + map signs (quests, water fountains, shops etc)

						if (type == 22) {
							if (object.getInteractType() != 1) continue;
						}

						// Wintertodt invisible non-collision walls. HOISTED above the
						// type dispatch so it covers loc type 9 as well: nested inside
						// the 0..3 branch it missed the separate type-9 branch below,
						// which painted the diagonal across the tile body. The flags
						// dumper never had the bug -- its wallish filter is
						// (type 0..3) || type == 9 with the id test after it -- so the
						// two dumpers disagreed about the same id. Measured over region
						// 19-55: 60 id-24720 locs, 48 of types 0-3 carved out by both,
						// 12 of type 9 wrongly painted, of which 4 are main diagonals
						// landing on the centre pixel terrain.py samples and 8 are anti
						// diagonals no centre-sampled census could surface.
						if (object.getId() == 24720) {
							continue;
						}

						int rotation = location.getOrientation();
						int drawX = (drawBaseX + localX) * MAP_SCALE;
						int drawY = (drawBaseY + (Region.Y - object.getSizeY() - localY)) * MAP_SCALE;

						if (type >= 0 && type <= 3)
						{
							int rgb = wallColor;
							if (object.getWallOrDoor() != 0) rgb = doorColor;

							if (drawX >= 0 && drawY >= 0 && drawX < image.getWidth() && drawY < image.getHeight())
							{
								if (type == 0 || type == 2)
								{
									EntityOpsDefinition.Op op = new EntityOpsDefinition.Op("Close");
									if (object.getWallOrDoor() == 0 || (object.getName().contains("urtain") || !object.getOps().getOps().contains(op))){
										for (int i = 0; i < MAP_SCALE; i++) {
											if (rotation == 0) image.setRGB(drawX, drawY + i, rgb);
											else if (rotation == 1) image.setRGB(drawX + i, drawY, rgb);
											else if (rotation == 2) image.setRGB(drawX + MAP_SCALE - 1, drawY + i, rgb);
											else if (rotation == 3) image.setRGB(drawX + i, drawY + MAP_SCALE - 1, rgb);
										}
									}

									else
									{
										for (int i = 0; i < MAP_SCALE; i++) {
											if (rotation == 0) image.setRGB(drawX + i, drawY, rgb);
											if (rotation == 1) image.setRGB(drawX + MAP_SCALE - 1, drawY + i, rgb);
											else if (rotation == 2) image.setRGB(drawX + i, drawY + MAP_SCALE, rgb);
											else if (rotation == 3) image.setRGB(drawX, drawY + i, rgb);
										}
									}
								}

								if (type == 3)
								{
									if (rotation == 0)      image.setRGB(drawX, drawY, rgb);
									else if (rotation == 1) image.setRGB(drawX + MAP_SCALE - 1, drawY, rgb);
									else if (rotation == 2) image.setRGB(drawX + MAP_SCALE - 1, drawY + MAP_SCALE - 1, rgb);
									else if (rotation == 3) image.setRGB(drawX, drawY + MAP_SCALE - 1, rgb);
								}

								if (type == 2)
								{
									for (int i = 0; i < MAP_SCALE; i++) {
										if (rotation == 0)      image.setRGB(drawX + i, drawY, rgb);
										else if (rotation == 1) image.setRGB(drawX + MAP_SCALE-1, drawY + i, rgb);
										else if (rotation == 2) image.setRGB(drawX + i, drawY + MAP_SCALE-1, rgb);
										else if (rotation == 3) image.setRGB(drawX, drawY + i, rgb);
									}
								}
							}
							continue;
						}

						if (type == 9)
						{
							//if (object.getMapSceneID() != -1) continue;
							int rgb = wallColor;
							if (object.getWallOrDoor() != 0) {
								rgb = doorColor;
							}

							for (int i = 0; i < MAP_SCALE; i++) {
								int y = (rotation != 0 && rotation != 2) ? drawY + i : drawY + (MAP_SCALE - 1 - i);
								image.setRGB(drawX + i, y, rgb);
							}
							continue;
						}

						if (type == 10 || type == 11 || (type >= 12 && type <= 22))
						{
							if (object.getName() == null) continue;
							if (object.getObjectModels() == null) continue;

							if (drawX < 0) continue;
							int xSize = object.getSizeX() * MAP_SCALE;
							int ySize = object.getSizeY() * MAP_SCALE;

							if ((xSize == ySize) || (rotation == 0 || rotation == 2))
							{
								if (drawX + xSize >= image.getWidth()) continue;
								if (drawY + ySize >= image.getHeight()) continue;

								for (int sX = 0; sX < xSize; sX++) {
									for (int sY = 0; sY < ySize; sY++) {
										if (image.getRGB(drawX + sX, drawY + sY) != wallColor) {
											image.setRGB(drawX + sX, drawY + sY, collisionColor);
										}
									}
								}
							}

							if (rotation == 1 || rotation == 3) {
								drawY = drawY + ySize - 1;
								if (drawY >= image.getHeight()) continue;
								if (drawY - xSize < 0) continue;
								if (drawX + ySize >= image.getWidth()) continue;
								for (int sX = 0; sX < xSize; sX++) {
									for (int sY = 0; sY < ySize; sY++) {
										if (image.getRGB(drawX + sY, drawY - sX) != wallColor) {
											image.setRGB(drawX + sY, drawY - sX, collisionColor);
										}
									}
								}
							}
						}
					}
				}
			}
		}
	}

	private ObjectDefinition findObject(int id)
	{
		return objectManager.getObject(id);
	}

	private void loadRegions() throws IOException
	{
		regionLoader.loadRegions();
		regionLoader.calculateBounds();

		log.debug("North most region: {}", regionLoader.getLowestY().getBaseY());
		log.debug("South most region: {}", regionLoader.getHighestY().getBaseY());
		log.debug("West most region:  {}", regionLoader.getLowestX().getBaseX());
		log.debug("East most region:  {}", regionLoader.getHighestX().getBaseX());
	}
}