package org.reactome.server.tools.reaction.exporter.renderer.canvas;

import java.awt.*;
import java.util.LinkedList;
import java.util.List;

/**
 * @author Lorente-Arencibia, Pascual (pasculorente@gmail.com)
 */
public class FillLayer extends CommonLayer {

	private final List<DrawObject> objects = new LinkedList<>();

	public void add(Shape shape, Paint color) {
		addShape(shape);
		objects.add(new DrawObject(shape, color));
	}

	@Override
	public void render(Graphics2D graphics) {
		objects.forEach(object -> {
			graphics.setPaint(object.color);
			graphics.fill(object.shape);
		});
	}

	@Override
	public void clear() {
		super.clear();
		objects.clear();
	}

	private class DrawObject {
		private final Shape shape;
		private final Paint color;

		DrawObject(Shape shape, Paint color) {
			this.shape = shape;
			this.color = color;
		}
	}
}
