package com.aid.common.aid.image;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** 按原图像素裁出独立的 PNG 图片，不做插值或模型处理。 */
public final class ImageRegionProcessor {
    public static final int MAX_OUTPUTS = 25;
    public static final long MAX_PIXELS = 20_000_000L;

    private ImageRegionProcessor() { }

    public record Region(int x, int y, int width, int height) { }
    public record Cell(int row, int column) { }
    public record Output(Region region, byte[] png) { }
    public record Result(int sourceWidth, int sourceHeight, List<Output> outputs) { }

    public static Region gridRegion(int width, int height, int rows, int columns, int row, int column) {
        if (rows < 1 || columns < 1 || row < 0 || row >= rows || column < 0 || column >= columns
                || width < columns || height < rows) {
            throw new IllegalArgumentException("宫格参数无效");
        }
        int x = (int) ((long) width * column / columns);
        int y = (int) ((long) height * row / rows);
        int right = (int) ((long) width * (column + 1) / columns);
        int bottom = (int) ((long) height * (row + 1) / rows);
        return new Region(x, y, right - x, bottom - y);
    }

    public static Result cropGrid(byte[] sourceBytes, int rows, int columns, List<Cell> cells) throws IOException {
        if (rows < 1 || rows > 5 || columns < 1 || columns > 5 || rows * columns < 2) {
            throw new IllegalArgumentException("宫格总数须为 2 到 25");
        }
        if (cells == null || cells.isEmpty() || cells.size() > rows * columns) {
            throw new IllegalArgumentException("宫格选区无效");
        }
        BufferedImage source = decode(sourceBytes);
        List<Region> regions = new ArrayList<>(cells.size());
        for (Cell cell : cells) {
            if (cell == null) throw new IllegalArgumentException("宫格选区无效");
            regions.add(gridRegion(source.getWidth(), source.getHeight(), rows, columns,
                    cell.row(), cell.column()));
        }
        return crop(source, regions);
    }

    public static Result crop(byte[] sourceBytes, List<Region> regions) throws IOException {
        return crop(decode(sourceBytes), regions);
    }

    private static BufferedImage decode(byte[] sourceBytes) throws IOException {
        if (sourceBytes == null || sourceBytes.length == 0) throw new IllegalArgumentException("原图为空");
        ImageIO.setUseCache(false);
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(sourceBytes))) {
            if (stream == null) throw new IllegalArgumentException("原图格式无效");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("原图格式无效");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
                    throw new IllegalArgumentException("原图尺寸无效");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Result crop(BufferedImage source, List<Region> regions) throws IOException {
        if (regions == null || regions.isEmpty()
                || regions.size() > MAX_OUTPUTS) {
            throw new IllegalArgumentException("切图参数无效");
        }
        List<Output> outputs = new ArrayList<>(regions.size());
        for (Region region : regions) {
            if (region == null || region.x() < 0 || region.y() < 0
                    || region.width() <= 0 || region.height() <= 0
                    || (long) region.x() + region.width() > source.getWidth()
                    || (long) region.y() + region.height() > source.getHeight()) {
                throw new IllegalArgumentException("裁切区域越界");
            }
            BufferedImage output = new BufferedImage(region.width(), region.height(),
                    source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.drawImage(source, 0, 0, region.width(), region.height(),
                        region.x(), region.y(), region.x() + region.width(), region.y() + region.height(), null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (!ImageIO.write(output, "png", bytes)) throw new IOException("PNG 编码器不可用");
            outputs.add(new Output(region, bytes.toByteArray()));
        }
        return new Result(source.getWidth(), source.getHeight(), List.copyOf(outputs));
    }
}
