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

import java.util.*;

import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer.getBounds;
import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer.move;
import static org.reactome.server.tools.reaction.exporter.layout.common.EntityRole.CATALYST;
import static org.reactome.server.tools.reaction.exporter.layout.common.EntityRole.INPUT;
import static org.reactome.server.tools.reaction.exporter.layout.common.GlyphUtils.hasRole;

/**
 * This is the crown jewel of the algorithms. Though it is still under high development, its results outperform any
 * other developed algorithm. The algorithm is divided in 3 steps: matrix generation, compaction and positioning.
 * <p><p>
 * <b>1. Matrix generation</b>
 * <p>
 * This part is performed by the {@link Box} class itself. A box is created by each compartment. A box can contain other
 * boxes (representing subcompartments) and glyphs. Boxes are designed to have enough space to layout all of its
 * content. A description of how it works can be found inside {@link Box} class.
 * <p><p>
 * <b>2. Compaction</b>
 * This step is performed by a dozen of short methods that remove empty columns and rows in the matrix, and move
 * elements in short steps so that they do not break any rule and represent a more compact view.
 * <p><p>
 * <b>3. Positioning</b>
 * Finally, we transform rows and columns into 'x's and 'y's, by computing columns and rows max widths and height and
 * centering each box into its 'x' and 'y'. Special attention to compartments in this step, as they can cause an
 * enlargement of widths when their name is too long.
 */
public class BoxAlgorithm {

    private static final double COMPARTMENT_PADDING = 20;

    private final Layout layout;
    private final LayoutIndex index;

    /**
     * Creates a BoxAlgorithm and prepares it to compute a layout. Use only one {@link BoxAlgorithm} per layout and call
     * {@link BoxAlgorithm#compute()} only once.
     */
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

    /**
     * Computes the position (dimension and coordinate) of every element in the Layout.
     */
    public void compute() {
        // Get the grid with all of the elements
        final Box box = new Box(layout.getCompartmentRoot(), index);
        // As elements are positioned with respect to the reaction, we need to first find the position of the reaction
        Point reactionPosition = box.placeReaction();
        box.placeElements(reactionPosition);
        final Div[][] preDivs = box.getDivs();
        final Grid<Div> grid = new Grid<>(Div.class, preDivs);

        removeEmptyRows(grid);
        removeEmptyCols(grid);

        Div[][] divs = grid.getGrid();
        // Get the parallel grid with the compartment of each position
        final CompartmentGlyph[][] comps = new CompartmentGlyph[divs.length][divs[0].length];
        computeCompartment(layout.getCompartmentRoot(), divs, comps);

        // Compaction
        reactionPosition = getReactionPosition(divs);
        compactLeft(divs, reactionPosition, comps);
        compactRight(divs, reactionPosition, comps);
        // compactTop(divs, reactionPosition, comps);
        // compactBottom(divs, reactionPosition, comps);

        // Me no like regulators on the same row as inputs, so me move them down
        divs = forceDiagonal(divs, reactionPosition);

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

        // usually, regulators are too widespread, let's compact them
        compactRegulators();

        ConnectorFactory.addConnectors(layout, index);
        layoutCompartments();
        removeExtracellular();
        computeDimension();
        moveToOrigin();
    }

    /**
     * Usually, regulators are too separated. This methods tries to close them a little bit, not only to get a more
     * compact view, but to avoid unnecessary segment crossings.
     */
    private void compactRegulators() {
        final List<EntityGlyph> regulators = new ArrayList<>(index.getRegulators());
        regulators.sort(Comparator.comparing(r -> r.getPosition().getCenterX()));
        final double centerX = layout.getReaction().getPosition().getCenterX();
        for (int i = 0; i < regulators.size(); i++) {
            final EntityGlyph regulator = regulators.get(i);
            // move regulators on the right
            if (regulator.getPosition().getCenterX() > centerX) {
                final double maxX = i == 0
                        ? layout.getReaction().getPosition().getCenterX() - regulator.getPosition().getWidth() * .5
                        : regulators.get(i - 1).getPosition().getMaxX() + 16;
                final double x = Math.max(maxX, getCompartmentX(regulator.getCompartment()));
                Transformer.move(regulator, x - regulator.getPosition().getX(), 0);
            }
        }
        for (int i = regulators.size() - 1; i >= 0; i--) {
            final EntityGlyph regulator = regulators.get(i);
            // move regulators on the left
            if (regulator.getPosition().getCenterX() < centerX) {
                final double minX = i == regulators.size() - 1
                        ? layout.getReaction().getPosition().getCenterX() - regulator.getPosition().getWidth() * 0.5
                        : regulators.get(i + 1).getPosition().getX() - 16;
                final double x = Math.min(minX, getCompartmentMaxX(regulator.getCompartment()));
                Transformer.move(regulator, x - regulator.getPosition().getMaxX(), 0);
            }
        }
    }

    /**
     * At this point, compartment sizes are not computed yet.
     */
    private double getCompartmentX(CompartmentGlyph compartment) {
        return compartment.getContainedGlyphs().stream()
                .map(Transformer::getBounds)
                .mapToDouble(Position::getX)
                .min().orElse(0.0);
    }

    /**
     * At this point, compartment sizes are not computed yet.
     */
    private double getCompartmentMaxX(CompartmentGlyph compartment) {
        return compartment.getContainedGlyphs().stream()
                .map(Transformer::getBounds)
                .mapToDouble(Position::getMaxX)
                .max().orElse(0.0);
    }

    /**
     * Avoid having catalysts in the same row as inputs or outputs
     */
    private Div[][] forceDiagonal(Div[][] divs, Point reactionPosition) {
        // TODO: 30/11/18 this would look nicer using the Grid
        // top/dows
        int r = 0;
        while (r < reactionPosition.getRow()) {
            boolean hasVertical = false;
            boolean hasHorizontal = false;
            for (int c = 0; c < divs[r].length; c++) {
                if (divs[r][c] instanceof VerticalLayout) hasVertical = true;
                if (divs[r][c] instanceof HorizontalLayout) hasHorizontal = true;
            }
            if (hasHorizontal && hasVertical) {
                divs = addRow(divs, r);
                reactionPosition.setRow(reactionPosition.getRow() + 1);
                for (int c = 0; c < divs[r].length; c++) {
                    if (divs[r + 1][c] instanceof HorizontalLayout) {
                        divs[r][c] = divs[r + 1][c];
                        divs[r + 1][c] = null;
                    }
                }
            }
            r++;
        }
        r = reactionPosition.getRow() + 1;
        while (r < divs.length) {
            boolean hasVertical = false;
            boolean hasHorizontal = false;
            for (int c = 0; c < divs[r].length; c++) {
                if (divs[r][c] instanceof VerticalLayout) hasVertical = true;
                if (divs[r][c] instanceof HorizontalLayout) hasHorizontal = true;
            }
            if (hasHorizontal && hasVertical) {
                divs = addRow(divs, r + 1);
                for (int c = 0; c < divs[r].length; c++) {
                    if (divs[r][c] instanceof HorizontalLayout) {
                        divs[r + 1][c] = divs[r][c];
                        divs[r][c] = null;
                    }
                }
            }
            r++;
        }
        return divs;
    }

    private Div[][] addRow(Div[][] divs, int row) {
        final Div[][] rtn = new Div[divs.length + 1][divs[0].length];
        if (row >= 0) System.arraycopy(divs, 0, rtn, 0, row);
        if (divs.length - row >= 0) System.arraycopy(divs, row, rtn, row + 1, divs.length - row);
        return rtn;

    }

    private void computeCompartment(CompartmentGlyph compartment, Div[][] divs, CompartmentGlyph[][] comps) {
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

    private void compactLeft(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = 0; row < divs.length; row++) {
            for (int col = 0; col < reactionPosition.getCol() - 1; col++) {
                // inputs
                if (divs[row][col] instanceof VerticalLayout) {
                    if (divs[row][col].getContainedRoles().contains(CATALYST)) continue;
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.getCol() - 1; c >= col + 1; c--) {
                        if (comps[row][c] == compartment || GlyphUtils.isAncestor(comps[row][c], compartment)) {
                            divs[row][c] = divs[row][col];
                            divs[row][col] = null;
                            break;
                        }
                    }
                    // only one VerticalLayout expected
                    break;
                    // catalyst/regulators
                } else if (divs[row][col] instanceof HorizontalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.getCol(); c >= col + 1; c--) {
                        if ((comps[row][c] == compartment || GlyphUtils.isAncestor(comps[row][c], compartment))) {
                            boolean busy = false;
                            for (int r = reactionPosition.getRow(); r < row; r++) {
                                if (divs[r][c] != null) {
                                    busy = true;
                                    break;
                                }
                            }
                            if (!busy) {
                                divs[row][c] = divs[row][col];
                                divs[row][col] = null;
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }
    }

    private void compactRight(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = 0; row < divs.length; row++) {
            for (int col = reactionPosition.getCol() + 1; col <= divs[0].length - 1; col++) {
                // 1 move outputs closer to reaction
                if (divs[row][col] instanceof VerticalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.getCol() + 1; c <= col - 1; c++) {
                        if (comps[row][c] == compartment || GlyphUtils.isAncestor(comps[row][c], compartment)) {
                            divs[row][c] = divs[row][col];
                            divs[row][col] = null;
                        }
                    }
                    break;
                } else if (divs[row][col] instanceof HorizontalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int c = reactionPosition.getCol(); c < col; c++) {
                        if (comps[row][c] == compartment || GlyphUtils.isAncestor(comps[row][c], compartment)) {
                            boolean busy = false;
                            for (int r = reactionPosition.getRow(); r < row; r++) {
                                if (divs[r][c] != null) {
                                    busy = true;
                                    break;
                                }
                            }
                            if (!busy) {
                                divs[row][c] = divs[row][col];
                                divs[row][col] = null;
                            }
                            break;
                        }
                    }
                    break;
                }
            }
        }
    }

    private void compactTop(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = reactionPosition.getRow() - 2; row >= 0; row--) {
            for (int col = 0; col < divs[0].length; col++) {
                if (divs[row][col] instanceof VerticalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int r = reactionPosition.getRow() - 1; r >= row + 1; r--) {
                        if (divs[r][col] != null) continue;
                        if (comps[r][col] == compartment || GlyphUtils.isAncestor(comps[r][col], compartment)) {
                            boolean busy = false;
                            for (int c = reactionPosition.getCol(); c < col; c++) {
                                if (divs[r][c] != null) {
                                    busy = true;
                                    break;
                                }
                            }
                            if (!busy) {
                                divs[r][col] = divs[row][col];
                                divs[row][col] = null;
                                break;
                            }
                        }
                    }
                    // only one VerticalLayout expected
                    break;
                }
            }
        }
    }

    private void compactBottom(Div[][] divs, Point reactionPosition, CompartmentGlyph[][] comps) {
        for (int row = reactionPosition.getRow() + 2; row < divs.length; row++) {
            for (int col = 0; col < divs[0].length; col++) {
                if (divs[row][col] instanceof VerticalLayout) {
                    final CompartmentGlyph compartment = comps[row][col];
                    for (int r = reactionPosition.getRow() + 1; r < divs.length; r++) {
                        if (comps[r][col] == compartment || GlyphUtils.isAncestor(comps[r][col], compartment)) {
                            boolean busy = false;
                            for (int c = reactionPosition.getCol(); c < col; c++) {
                                if (divs[r][c] != null) {
                                    busy = true;
                                    break;
                                }
                            }
                            if (!busy) {
                                divs[r][col] = divs[row][col];
                                divs[row][col] = null;
                                break;
                            }
                        }
                    }
                    // only one VerticalLayout expected
                    break;
                }
            }
        }
    }

    /**
     * Calculates the absolute position of the reaction in the divs
     */
    private Point getReactionPosition(Div[][] divs) {
        for (int r = 0; r < divs.length; r++) {
            for (int c = 0; c < divs[0].length; c++) {
                if (divs[r] != null && divs[r][c] != null) {
                    final Div div = divs[r][c];
                    if (div instanceof GlyphsLayout) {
                        final GlyphsLayout layout = (GlyphsLayout) div;
                        if (layout.getGlyphs().iterator().next() instanceof ReactionGlyph) {
                            return new Point(r, c);
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Adds extra space in widths and heights for compartments.
     * @param compartment which compartment to expand
     * @param divs   the grid
     * @param widths the array with widths
     * @param heights the array with heights
     */
    private void expandCompartment(CompartmentGlyph compartment, Div[][] divs, double[] widths, double[] heights) {
        for (final CompartmentGlyph child : compartment.getChildren()) {
            expandCompartment(child, divs, widths, heights);
        }
        int minCol = widths.length - 1;
        int maxCol = 0;
        int minRow = heights.length - 1;
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
        final double minWidth = 2 * COMPARTMENT_PADDING + FontProperties.getTextWidth(compartment.getName());
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
        if (compartment.getContainedGlyphs().stream()
                .filter(EntityGlyph.class::isInstance)
                .anyMatch(glyph -> hasRole((EntityGlyph) glyph, INPUT, CATALYST))) {
            heights[minRow] += 50; // TODO: 29/11/18 find a better approach
        }

    }

    private void removeEmptyRows(Grid<Div> divs) {
        int r = 0;
        while (r < divs.getRows()) {
            if (Arrays.stream(divs.getRow(r)).allMatch(Objects::isNull)) {
                divs.removeRows(r, 1);
            } else r++;
        }
    }

    private void removeEmptyCols(Grid<Div> divs) {
        int c = 0;
        while (c < divs.getColumns()) {
            if (Arrays.stream(divs.getColumn(c)).allMatch(Objects::isNull)) {
                divs.removeColumns(c, 1);
            } else c++;
        }
    }

    private void layoutCompartments() {
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
    private void removeExtracellular() {
        layout.getCompartments().remove(layout.getCompartmentRoot());
    }

    /**
     * Compute the absolute dimension of the layout.
     */
    private void computeDimension() {
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

    /**
     * Modify all positions so the layout.position is (x,y)=(0,0)
     */
    private void moveToOrigin() {
        final double dx = -layout.getPosition().getX();
        final double dy = -layout.getPosition().getY();
        final Coordinate delta = new CoordinateImpl(dx, dy);
        layout.getPosition().move(dx, dy);
        move(layout.getCompartmentRoot(), delta, true);
    }
}
