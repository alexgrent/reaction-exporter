package org.reactome.server.tools.reaction.exporter.layout.algorithm.breathe;

import org.reactome.server.tools.diagram.data.layout.Coordinate;
import org.reactome.server.tools.diagram.data.layout.Segment;
import org.reactome.server.tools.diagram.data.layout.impl.CoordinateImpl;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Dedup;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.FontProperties;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.LayoutIndex;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer;
import org.reactome.server.tools.reaction.exporter.layout.common.GlyphUtils;
import org.reactome.server.tools.reaction.exporter.layout.common.Position;
import org.reactome.server.tools.reaction.exporter.layout.model.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer.getBounds;
import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer.move;
import static org.reactome.server.tools.reaction.exporter.layout.common.EntityRole.CATALYST;
import static org.reactome.server.tools.reaction.exporter.layout.common.EntityRole.INPUT;
import static org.reactome.server.tools.reaction.exporter.layout.common.GlyphUtils.hasRole;

public class BoxAlgorithm {

    private static final double COMPARTMENT_PADDING = 20;

    private final Layout layout;
    private final LayoutIndex index;

    public BoxAlgorithm(Layout layout) {
        this.layout = layout;
        Dedup.addDuplicates(layout);
        index = new LayoutIndex(layout);
        fixReactionWithNoCompartment(layout);
    }

    /**
     * This fixes reaction R-HSA-9006323 that does not contain a compartment. It can also happen in other species.
     */
    private void fixReactionWithNoCompartment(Layout layout) {
        if (layout.getReaction().getCompartment() == null) {
            layout.getReaction().setCompartment(layout.getCompartmentRoot());
            layout.getCompartmentRoot().getContainedGlyphs().add(layout.getReaction());
        }
    }

    public void compute() {
        final Box box = new Box(layout.getCompartmentRoot(), index);
        final Point reactionPosition = box.placeReaction();
        box.placeElements(reactionPosition);
        final Div[][] preDivs = box.getDivs();
        final Div[][] divs = compactCols(compactRows(preDivs));
        final CompartmentGlyph[][] comps = new CompartmentGlyph[divs.length][divs[0].length];
        computeCompartment(layout.getCompartmentRoot(), divs, comps);
        final Point p = getReactionPosition(divs);
        compactInputs(divs, p, comps);
        compactOutputs(divs, p, comps);

        // size every square
        final int rows = divs.length;
        final double heights[] = new double[rows];
        final int cols = divs[0].length;
        final double widths[] = new double[cols];
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final Div div = divs[row][col];
                if (div == null) continue;
                final Position bounds = div.getBounds();
                if (bounds.getWidth() > widths[col]) widths[col] = bounds.getWidth();
                if (bounds.getHeight() > heights[row]) heights[row] = bounds.getHeight();
            }
        }
        // add space for compartment padding and extra large compartments (long text)
        expandCompartment(layout.getCompartmentRoot(), divs, widths, heights);

        // get centers by row and column
        final double cy[] = new double[rows];
        cy[0] = 0.5 * heights[0];
        for (int i = 1; i < rows; i++) {
            cy[i] = cy[i - 1] + 0.5 * heights[i - 1] + 0.5 * heights[i];
        }
        final double cx[] = new double[cols];
        cx[0] = 0.5 * widths[0];
        for (int i = 1; i < cols; i++) {
            cx[i] = cx[i - 1] + 0.5 * widths[i - 1] + 0.5 * widths[i];
        }

        // place things (wheeeee!!)
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                final Div div = divs[row][col];
                if (div == null) continue;
                div.center(cx[col], cy[row]);
            }
        }

        ConnectorFactory.addConnectors(layout, index);
        layoutCompartments(layout);
        removeExtracellular(layout);
        computeDimension(layout);
        moveToOrigin(layout);
    }

    private void computeCompartment(CompartmentGlyph compartment, Div[][] divs,CompartmentGlyph[][] comps ) {
        for (final CompartmentGlyph child : compartment.getChildren()) {
            computeCompartment(child, divs, comps);
        }
        int minCol = Integer.MAX_VALUE;
        int maxCol = 0;
        int minRow = Integer.MAX_VALUE;
        int maxRow = 0;
        for (int r = 0; r < divs.length; r++) {
            for (int c = 0; c < divs[0].length; c++) {
                final Div div = divs[r][c];
                if (div == null) continue;
                if (compartment == div.getCompartment() || GlyphUtils.isAncestor(compartment, div.getCompartment())) {
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                }
            }
        }
        for (int r = minRow; r <= maxRow; r++) {
            for (int c = minCol; c <= maxCol; c++) {
                if (comps[r][c] == null) comps[r][c] = compartment;
            }
        }
    }

    private void compactInputs(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = 0; row < divs.length; row++) {
            for (int col = 0; col < reactionPosition.x - 1; col++) {
                // we know inputs are laid out using VerticalLayout
                if (divs[row][col] instanceof VerticalLayout) {
                    if (divs[row][col].getContainedRoles().contains(CATALYST)) continue;
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.x - 1; c >= col + 1; c--) {
                        if (comps[row][c] == compartment) {
                            divs[row][c] = divs[row][col];
                            divs[row][col] = null;
                            break;
                        }
                    }
                    // only one VerticalLayout expected
                    break;
                }
            }
        }
    }

    private void compactOutputs(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = 0; row < divs.length; row++) {
            for (int col = divs[0].length - 1; col > reactionPosition.x + 1; col--) {
                // we know outputs are laid out using VerticalLayout
                if (divs[row][col] instanceof VerticalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.x + 1; c < col; c++) {
                        if (comps[row][c] == compartment) {
                            divs[row][c] = divs[row][col];
                            divs[row][col] = null;
                            break;
                        }
                    }
                    // only one VerticalLayout expected
                    break;
                }
            }
        }
    }

    private Point getReactionPosition(Div[][] divs) {
        for (int y = 0; y < divs.length; y++) {
            for (int x = 0; x < divs[0].length; x++) {
                if (divs[y] != null && divs[y][x] != null) {
                    final Div div = divs[y][x];
                    if (div instanceof GlyphsLayout) {
                        final GlyphsLayout layout = (GlyphsLayout) div;
                        if (layout.getGlyphs().iterator().next() instanceof ReactionGlyph) {
                            return new Point(x, y);
                        }
                    }
                }
            }
        }
        return null;
    }

    private void expandCompartment(CompartmentGlyph compartment, Div[][] divs, double[] widths, double[] heights) {
        for (final CompartmentGlyph child : compartment.getChildren()) {
            expandCompartment(child, divs, widths, heights);
        }
        int minCol = Integer.MAX_VALUE;
        int maxCol = 0;
        int minRow = Integer.MAX_VALUE;
        int maxRow = 0;
        for (int r = 0; r < divs.length; r++) {
            for (int c = 0; c < divs[0].length; c++) {
                final Div div = divs[r][c];
                if (div == null) continue;
                if (compartment == div.getCompartment() || GlyphUtils.isAncestor(compartment, div.getCompartment())) {
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                }
            }
        }
        double width = 0;
        for (int i = minCol; i <= maxCol; i++) {
            width += widths[i];
        }
        final double minWidth = getCompartmentMinWidth(compartment);
        if (width < minWidth) {
            final double factor = minWidth / width;
            for (int i = minCol; i <= maxCol; i++) {
                widths[i] *= factor;
            }
        } else {
            widths[minCol] += COMPARTMENT_PADDING;
            widths[maxCol] += COMPARTMENT_PADDING;
        }
        heights[minRow] += COMPARTMENT_PADDING;
        heights[maxRow] += COMPARTMENT_PADDING;

    }

    private double getCompartmentMinWidth(CompartmentGlyph compartment) {
        return 2 * COMPARTMENT_PADDING + FontProperties.getTextWidth(compartment.getName());
    }

    private Div[][] compactRows(Div[][] divs) {
        final List<Div[]> rtn = new ArrayList<>();
        for (final Div[] div : divs)
            if (div != null && Arrays.stream(div).anyMatch(Objects::nonNull)) rtn.add(div);

        return rtn.toArray(new Div[rtn.size()][]);
    }

    private Div[][] compactCols(Div[][] divs) {
        final List<Div>[] semiMatrix = new List[divs.length];
        for (int i = 0; i < semiMatrix.length; i++)
            semiMatrix[i] = new ArrayList<>();
        for (int c = 0; c < divs[0].length; c++) {
            if (columnIsBusy(divs, c)) {
                for (int i = 0; i < divs.length; i++) {
                    final Div[] div = divs[i];
                    if (div != null && div[c] != null) {
                        semiMatrix[i].add(div[c]);
                    } else semiMatrix[i].add(null);
                }
            }
        }
        final Div[][] rtn = new Div[semiMatrix.length][];
        for (int i = 0; i < semiMatrix.length; i++) {
            final List<Div> list = semiMatrix[i];
            rtn[i] = list.toArray(new Div[list.size()]);
        }
        return rtn;
    }

    private boolean columnIsBusy(Div[][] divs, int c) {
        for (final Div[] div : divs)
            if (div != null && div[c] != null)
                return true;
        return false;
    }

    private void layoutCompartments(Layout layout) {
        layoutCompartment(layout.getCompartmentRoot());
    }

    /**
     * Calculates the size of the compartments so each of them surrounds all of its contained glyphs and children.
     */
    private void layoutCompartment(CompartmentGlyph compartment) {
        Position position = null;
        for (CompartmentGlyph child : compartment.getChildren()) {
            layoutCompartment(child);
            if (position == null) position = new Position(child.getPosition());
            else position.union(child.getPosition());
        }
        for (Glyph glyph : compartment.getContainedGlyphs()) {
            final Position bounds = glyph instanceof ReactionGlyph
                    ? Transformer.padd(getBounds(glyph), 80, 40)
                    : getBounds(glyph);
            if (position == null) position = new Position(bounds);
            else position.union(bounds);
            if (glyph instanceof EntityGlyph) {
                final EntityGlyph entityGlyph = (EntityGlyph) glyph;
                if (hasRole(entityGlyph, CATALYST, INPUT)) {
                    double topy = entityGlyph.getPosition().getY();
                    for (final Segment segment : entityGlyph.getConnector().getSegments()) {
                        if (segment.getFrom().getY() < topy) topy = segment.getFrom().getY();
                    }
                    position.union(new Position(entityGlyph.getPosition().getX(), topy, 1, 1));
                }
            }
        }
        position.setX(position.getX() - COMPARTMENT_PADDING);
        position.setY(position.getY() - COMPARTMENT_PADDING);
        position.setWidth(position.getWidth() + 2 * COMPARTMENT_PADDING);
        position.setHeight(position.getHeight() + 2 * COMPARTMENT_PADDING);

        final double textWidth = FontProperties.getTextWidth(compartment.getName());
        final double textHeight = FontProperties.getTextHeight();
        final double textPadding = textWidth + 30;
        // If the text is too large, we increase the size of the compartment
        if (position.getWidth() < textPadding) {
            double diff = textPadding - position.getWidth();
            position.setWidth(textPadding);
            position.setX(position.getX() - 0.5 * diff);
        }
        // Puts text in the bottom right corner of the compartment
        final Coordinate coordinate = new CoordinateImpl(
                position.getMaxX() - textWidth - 15,
                position.getMaxY() + 0.5 * textHeight - COMPARTMENT_PADDING);
        compartment.setLabelPosition(coordinate);
        compartment.setPosition(position);
    }

    /**
     * This operation should be called in the last steps, to avoid being exported to a Diagram object.
     */
    private void removeExtracellular(Layout layout) {
        layout.getCompartments().remove(layout.getCompartmentRoot());
    }

    private void computeDimension(Layout layout) {
        Position position = null;
        for (CompartmentGlyph compartment : layout.getCompartments()) {
            if (position == null) position = new Position(compartment.getPosition());
            else position.union(compartment.getPosition());
        }
        for (EntityGlyph entity : layout.getEntities()) {
            final Position bounds = Transformer.getBounds(entity);
            if (position == null) position = new Position(bounds);
            else position.union(bounds);
            for (final Segment segment : entity.getConnector().getSegments()) {
                final double minX = Math.min(segment.getFrom().getX(), segment.getTo().getX());
                final double maxX = Math.max(segment.getFrom().getX(), segment.getTo().getX());
                final double minY = Math.min(segment.getFrom().getY(), segment.getTo().getY());
                final double maxY = Math.max(segment.getFrom().getY(), segment.getTo().getY());
                position.union(new Position(minX, minY, maxX - minX, maxY - minY));
            }
        }
        final Position bounds = Transformer.getBounds(layout.getReaction());
        if (position == null) position = new Position(bounds);
        else position.union(bounds);
        layout.getPosition().set(position);
    }

    private void moveToOrigin(Layout layout) {
        final double dx = -layout.getPosition().getX();
        final double dy = -layout.getPosition().getY();
        final Coordinate delta = new CoordinateImpl(dx, dy);
        layout.getPosition().move(dx, dy);
        move(layout.getCompartmentRoot(), delta, true);
    }
}