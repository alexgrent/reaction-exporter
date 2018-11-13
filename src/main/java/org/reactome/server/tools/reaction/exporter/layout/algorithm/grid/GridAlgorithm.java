package org.reactome.server.tools.reaction.exporter.layout.algorithm.grid;

import org.reactome.server.tools.diagram.data.layout.Coordinate;
import org.reactome.server.tools.diagram.data.layout.Segment;
import org.reactome.server.tools.diagram.data.layout.Shape;
import org.reactome.server.tools.diagram.data.layout.Stoichiometry;
import org.reactome.server.tools.diagram.data.layout.impl.*;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.LayoutAlgorithm;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.FontProperties;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.LayoutIndex;
import org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer;
import org.reactome.server.tools.reaction.exporter.layout.common.EntityRole;
import org.reactome.server.tools.reaction.exporter.layout.common.Position;
import org.reactome.server.tools.reaction.exporter.layout.common.RenderableClass;
import org.reactome.server.tools.reaction.exporter.layout.model.*;

import java.util.*;
import java.util.stream.Collectors;

import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Dedup.addDuplicates;
import static org.reactome.server.tools.reaction.exporter.layout.algorithm.common.Transformer.*;
import static org.reactome.server.tools.reaction.exporter.layout.common.EntityRole.*;

/**
 * Standard layout algorithm. It places inputs on the left, outputs on the right, catalysts on top and regulators on the
 * bottom. Every group of participants is placed in a line perpendicular to the edge joining the center of the group to
 * the reaction, i.e. inputs and outputs vertically, catalysts and regulators horizontally. When a group of participants
 * lays into more than one compartment, the are placed into parallel lines, one behind the other, so compartments have
 * space to be drawn. At last, reaction is placed in the centre of the diagram, unless it falls into a compartment it
 * does not belong to. In that case, it is moved to a safer position. In order of priority, it's moved towards inputs,
 * if not possible towards outputs, then catalysts and regulators.
 */
@SuppressWarnings("Duplicates")
public class GridAlgorithm implements LayoutAlgorithm {

    private static final int COLUMN_PADDING = 20;
    /**
     * Length of the backbone of the reaction
     */
    private static final double BACKBONE_LENGTH = 20;
    /**
     * Size of the box surrounding regulator and catalysts shapes
     */
    private static final int REGULATOR_SIZE = 6;
    /**
     * Minimum length of segments departing participants.
     */
    private static final int MIN_SEGMENT = 35;
    /**
     * Minimum distance between the compartment border and any of ints contained glyphs.
     */
    private static final double COMPARTMENT_PADDING = 20;
    /**
     * Minimum allocated height for any glyph. Even if glyphs have a lower height, they are placed in the middle of this
     * minimum distance.
     */
    private static final double MIN_GLYPH_HEIGHT = 25;
    /**
     * Minimum allocated width for any glyph. Even if glyphs have a lower width, they are placed in the middle of this
     * minimum distance.
     */
    private static final double MIN_GLYPH_WIDTH = 60;
    /**
     * Vertical (y-axis) distance between two glyphs.
     */
    private static final double VERTICAL_PADDING = 12;
    /**
     * Horizontal (x-axis) distance between two glyphs.
     */
    private static final double HORIZONTAL_PADDING = 12;
    /**
     * Minimum vertical distance between any glyph and the reaction glyph
     */
    private static final double REACTION_MIN_V_DISTANCE = 60;
    /**
     * Minimum horizontal distance between any glyph and the reaction glyph
     */
    private static final double REACTION_MIN_H_DISTANCE = 120;
    /**
     * Comparator that puts false (and null) elements before true elements.
     */
    private static final Comparator<Boolean> FALSE_FIRST = Comparator.nullsFirst((o1, o2) -> o1.equals(o2) ? 0 : o1 ? 1 : -1);
    private static final Comparator<Boolean> TRUE_FIRST = Comparator.nullsLast((o1, o2) -> o1.equals(o2) ? 0 : o1 ? -1 : 1);
    private static final Comparator<CompartmentGlyph> OUTER_FIRST = (o1, o2) -> {
        if (isDescendant(o1, o2)) return -1;
        else if (isDescendant(o2, o1)) return 1;
        return 0;
    };
    private static final double ARROW_SIZE = 8;
    private LayoutIndex index;

    private static boolean isDescendant(CompartmentGlyph root, CompartmentGlyph compartment) {
        CompartmentGlyph parent = compartment.getParent();
        while (parent != null) {
            if (parent == root) return true;
            parent = parent.getParent();
        }
        return false;
    }

    @Override
    public void compute(Layout layout) {
        addDuplicates(layout);
        index = new LayoutIndex(layout);
        layoutParticipants(layout);
        layoutCompartments(layout);
        layoutConnectors(layout);
        removeExtracellular(layout);
        computeDimension(layout);
        moveToOrigin(layout);
    }

    private List<CompartmentGlyph> getVerticallyOrderedCompartments(Layout layout) {
        final List<CompartmentGlyph> compartments = new ArrayList<>(layout.getCompartments());
        compartments.sort((o1, o2) -> Comparator
                .comparing(((CompartmentGlyph cg) -> containsRole(cg, CATALYST)), TRUE_FIRST)
                .thenComparing(cg -> containsRole(cg, NEGATIVE_REGULATOR), FALSE_FIRST)
                .thenComparing(cg -> containsRole(cg, POSITIVE_REGULATOR), FALSE_FIRST)
                .thenComparing(OUTER_FIRST)
                .compare(o1, o2));
        return compartments;
    }

    private List<CompartmentGlyph> getHorizontallyOrderedCompartments(Layout layout) {
        final List<CompartmentGlyph> compartments = new ArrayList<>(layout.getCompartments());
        compartments.sort((o1, o2) -> Comparator
                .comparing(((CompartmentGlyph cg) -> containsRole(cg, INPUT)), TRUE_FIRST)
                .thenComparing(cg -> containsRole(cg, OUTPUT), FALSE_FIRST)
                .thenComparing(OUTER_FIRST)
                .compare(o1, o2));
        return compartments;
    }

    private boolean containsRole(CompartmentGlyph compartment, EntityRole role) {
        for (Glyph glyph : compartment.getContainedGlyphs()) {
            if (glyph instanceof EntityGlyph && hasRole((EntityGlyph) glyph, role)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasRole(EntityGlyph glyph, EntityRole role) {
        return glyph.getRoles().stream().anyMatch(r -> r.getType() == role);
    }

    private void layoutParticipants(Layout layout) {
        Map<CompartmentGlyph, Map<EntityRole, Tile>> board = new HashMap<>();

        // Currently this method cannot be split into submethods since it uses several codependent variables
        for (EntityGlyph entity : layout.getEntities()) setSize(entity);

        // We will layout by row, every row is a compartment
        final List<CompartmentGlyph> verticalCompartments = getVerticallyOrderedCompartments(layout);
        int row = 0;

        // catalysts
        for (final CompartmentGlyph compartment : verticalCompartments) {
            final List<Glyph> catalysts = index.getCatalysts().stream()
                    .filter(entityGlyph -> entityGlyph.getCompartment() == compartment)
                    .collect(Collectors.toList());
            final Position c = horizontal(catalysts);
            if (!catalysts.isEmpty()) {
                final Tile tile = board.computeIfAbsent(compartment, comp -> new HashMap<>())
                        .computeIfAbsent(CATALYST, r -> new Tile(catalysts, c));
                tile.row = row++;
            }
        }
        // inputs, outputs, reaction
        for (final CompartmentGlyph compartment : verticalCompartments) {
            final List<Glyph> inputs = index.getInputs().stream()
                    .filter(entityGlyph -> entityGlyph.getCompartment() == compartment)
                    .collect(Collectors.toList());
            final Position i = vertical(inputs);
            if (!inputs.isEmpty()) {
                final Tile tile = board.computeIfAbsent(compartment, comp -> new HashMap<>())
                        .computeIfAbsent(INPUT, r -> new Tile(inputs, i));
                tile.row = row;
            }
            final List<Glyph> outputs = index.getOutputs().stream()
                    .filter(entityGlyph -> entityGlyph.getCompartment() == compartment)
                    .collect(Collectors.toList());
            final Position o = vertical(outputs);
            if (!outputs.isEmpty()) {
                final Tile tile = board.computeIfAbsent(compartment, comp -> new HashMap<>())
                        .computeIfAbsent(OUTPUT, r -> new Tile(outputs, o));
                tile.row = row;
            }
            final List<Glyph> reaction = new ArrayList<>();
            if (layout.getReaction().getCompartment() == compartment) {
                final Position r = reaction(layout.getReaction());
                reaction.add(layout.getReaction());
                final Tile tile = board.computeIfAbsent(compartment, comp -> new HashMap<>())
                        .computeIfAbsent(NEGATIVE_REGULATOR, role -> new Tile(reaction, r));
                tile.row = row;
            }
            if (!inputs.isEmpty() || !outputs.isEmpty() || !reaction.isEmpty()) row++;
        }
        // regulators
        Collections.reverse(verticalCompartments);
        for (final CompartmentGlyph compartment : verticalCompartments) {
            final List<Glyph> regulators = index.getRegulators().stream()
                    .filter(entityGlyph -> entityGlyph.getCompartment() == compartment)
                    .collect(Collectors.toList());
            final Position reg = horizontal(regulators);
            if (!regulators.isEmpty()) {
                final Tile tile = board.computeIfAbsent(compartment, comp -> new HashMap<>())
                        .computeIfAbsent(POSITIVE_REGULATOR, role -> new Tile(regulators, reg));
                tile.row = row++;
            }
        }
        // inputs
        final List<CompartmentGlyph> horizontalCompartments = getHorizontallyOrderedCompartments(layout);
        int column = 0;
        for (final CompartmentGlyph compartment : horizontalCompartments) {
            final Tile tile = board.getOrDefault(compartment, Collections.emptyMap()).get(INPUT);
            if (tile != null) tile.column = column++;
        }
        for (final CompartmentGlyph compartment : horizontalCompartments) {
            final Tile cat = board.getOrDefault(compartment, Collections.emptyMap()).get(CATALYST);
            if (cat != null) cat.column = column;
            final Tile react = board.getOrDefault(compartment, Collections.emptyMap()).get(NEGATIVE_REGULATOR); // reaction
            if (react != null) react.column = column;
            final Tile reg = board.getOrDefault(compartment, Collections.emptyMap()).get(POSITIVE_REGULATOR);
            if (reg != null) reg.column = column;
            if (cat != null || react != null || reg != null) column++;
        }
        Collections.reverse(horizontalCompartments);
        for (final CompartmentGlyph compartment : horizontalCompartments) {
            final Tile tile = board.getOrDefault(compartment, Collections.emptyMap()).get(OUTPUT);
            if (tile != null) tile.column = column++;
        }

        final Tile[][] tiling = new Tile[row][column];
        board.forEach((comp, tileMap) -> tileMap.forEach((role, tile) -> {
            tiling[tile.row][tile.column] = tile;
        }));
        setPositions((tiling));

    }

    private void setPositions(Tile[][] tiles) {
        // Compute overall widths and heights to center elements
        int rows = tiles.length;
        int cols = tiles[0].length;
        double[] ws = new double[cols];
        double[] hs = new double[rows];
        Arrays.fill(ws, 0);
        Arrays.fill(hs, 0);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                final Tile tile = tiles[r][c];
                if (tile != null) {
                    if (tile.bounds.getWidth() > ws[c]) ws[c] = tile.bounds.getWidth();
                    if (tile.bounds.getHeight() > hs[r]) hs[r] = tile.bounds.getHeight();
                }
            }
        }
        double y = 0;
        for (int r = 0; r < rows; r++) {
            final double cy = y + 0.5 * hs[r];
            double x = 0;
            for (int c = 0; c < cols; c++) {
                final Tile tile = tiles[r][c];
                if (tile != null) {
                    double cx = x + 0.5 * ws[c];
                    final Position position = tile.getBounds();
                    double dx = cx - position.getCenterX();
                    double dy = cy - position.getCenterY();
                    final CoordinateImpl delta = new CoordinateImpl(dx, dy);
                    for (final Glyph glyph : tile.getGlyphs()) {
                        Transformer.move(glyph, delta);
                    }

                }
                x += ws[c];
            }
            y += hs[r];
        }
    }

    private Position reaction(ReactionGlyph reaction) {
        Transformer.setSize(reaction);
        // Add backbones
        final Position position = reaction.getPosition();
        reaction.getSegments().add(new SegmentImpl(
                position.getX(), position.getCenterY(),
                position.getX() - BACKBONE_LENGTH, position.getCenterY()));
        reaction.getSegments().add(new SegmentImpl(
                position.getMaxX(), position.getCenterY(),
                position.getMaxX() + BACKBONE_LENGTH, position.getCenterY()));
        return Transformer.padd(Transformer.getBounds(reaction), REACTION_MIN_H_DISTANCE, REACTION_MIN_V_DISTANCE);
    }

    private Position horizontal(List<Glyph> glyphs) {
        if (glyphs.isEmpty()) return new Position();
        final double height = glyphs.stream().map(Transformer::getBounds).mapToDouble(Position::getHeight).max().orElse(MIN_GLYPH_HEIGHT);
        final double y = 0.5 * (height + VERTICAL_PADDING);
        double x = 0;
        for (final Glyph glyph : glyphs) {
            final double width = Transformer.getBounds(glyph).getWidth();
            Transformer.center(glyph, new CoordinateImpl(x + 0.5 * (HORIZONTAL_PADDING + width), y));
            x += HORIZONTAL_PADDING + width;
        }
        return Transformer.padd(new Position(0d, 0d, x, height + VERTICAL_PADDING), COMPARTMENT_PADDING);
    }

    private Position vertical(List<Glyph> glyphs) {
        if (glyphs.size() > 6) return layoutInTwoColumns(glyphs);
        if (glyphs.isEmpty()) return new Position();
        final double width = glyphs.stream().map(Transformer::getBounds).mapToDouble(Position::getWidth).max().orElse(MIN_GLYPH_WIDTH);
        final double x = 0.5 * (width + HORIZONTAL_PADDING);
        double y = 0;
        for (final Glyph glyph : glyphs) {
            final double height = Transformer.getBounds(glyph).getHeight();
            Transformer.center(glyph, new CoordinateImpl(x, y + 0.5 * (VERTICAL_PADDING + height)));
            y += VERTICAL_PADDING + height;
        }
        return Transformer.padd(new Position(0d, 0d, width + HORIZONTAL_PADDING, y), COMPARTMENT_PADDING);
    }

    private Position layoutInTwoColumns(List<Glyph> glyphs) {
        // the width of each column
        final int columns = 2;
        double[] widths = new double[columns];
        Arrays.fill(widths, 0);
        for (int i = 0; i < glyphs.size(); i++) {
            final double width = getBounds(glyphs.get(i)).getWidth();
            final int j = i % columns;
            if (width > widths[j]) widths[j] = width;
        }
        // the x for each column (the center)
        final double[] xs = new double[columns];
        Arrays.fill(xs, 0);
        xs[0] = 0.5 * widths[0];
        for (int i = 1; i < columns; i++) {
            xs[i] = xs[i - 1] + 0.5 * widths[i - 1] + 0.5 * widths[i] + COLUMN_PADDING;
        }
        final double height = glyphs.stream().map(Transformer::getBounds).mapToDouble(Position::getHeight).max().orElse(MIN_GLYPH_HEIGHT);
        final double step = 0.5 * (height + VERTICAL_PADDING);
        final Position limits = new Position();
        double y = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            final Glyph glyph = glyphs.get(i);
            y += step;
            final double x = xs[i % columns];
            Transformer.center(glyph, new CoordinateImpl(x, y));
            limits.union(Transformer.getBounds(glyph));
        }
        return limits;
    }

    private Tile[][] compact(Tile[][] tiling) {
        final List<Tile[]> tiles = new ArrayList<>();
        Collections.addAll(tiles, tiling);
        // Compact by row
        int row = 1;
        int deleted = 0;
        while (row < tiles.size()) {
            boolean canMerge = true;
            for (int col = 0; col < tiling[row].length; col++) {
                if (tiles.get(row - 1)[col] != null && tiles.get(row)[col] != null) {
                    canMerge = false;
                    break;
                }
            }
            if (canMerge) {
                for (int col = 0; col < tiles.get(row).length; col++)
                    if (tiles.get(row - 1)[col] == null && tiles.get(row)[col] != null)
                        tiles.get(row - 1)[col] = tiles.get(row)[col];
                tiles.remove(row);
            } else row++;
            deleted++;
        }
        // // Make a copy removing unnecessary rows
        // Tile[][] tiles = new Tile[tiling.length - deleted][tiling[0].length];
        // System.arraycopy(tiling, 0, tiles, 0, row - deleted);

        return tiles.toArray(new Tile[tiles.size()][]);
    }

    private void layoutConnectors(Layout layout) {
        inputConnectors(layout);
        outputConnectors(layout);
        catalystConnectors(layout);
        regulatorConnectors(layout);
    }

    private void inputConnectors(Layout layout) {
        // we have to deal with the case that inputs can be catalysts as well, in that case two connector have to be
        // created. This method supposes that the inputs are on the top left corner of the diagram.
        final Position reactionPosition = layout.getReaction().getPosition();
        final double mx = index.getInputs().stream().map(Transformer::getBounds).mapToDouble(Position::getMaxX).max().orElse(0);
        final double vRule = mx + MIN_SEGMENT;
        final double port = reactionPosition.getX() - BACKBONE_LENGTH;
        for (EntityGlyph entity : index.getInputs()) {
            final Position position = entity.getPosition();
            // is catalyst and input
            final boolean biRole = entity.getRoles().size() > 1;
            final ConnectorImpl connector = createConnector(entity);
            final List<Segment> segments = connector.getSegments();
            // Input
            if (entity.getRenderableClass() == RenderableClass.GENE) {
                // Genes need an extra segment from the arrow
                segments.add(new SegmentImpl(position.getMaxX() + 8, position.getY(),
                        position.getMaxX() + 30, position.getCenterY()));
                segments.add(new SegmentImpl(
                        new CoordinateImpl(position.getMaxX() + 30, position.getCenterY()),
                        new CoordinateImpl(vRule, position.getCenterY())));
                segments.add(new SegmentImpl(
                        new CoordinateImpl(vRule, position.getCenterY()),
                        new CoordinateImpl(port, reactionPosition.getCenterY())));
            } else {
                segments.add(new SegmentImpl(
                        new CoordinateImpl(position.getMaxX(), position.getCenterY()),
                        new CoordinateImpl(vRule, position.getCenterY())));
                segments.add(new SegmentImpl(
                        new CoordinateImpl(vRule, position.getCenterY()),
                        new CoordinateImpl(port, reactionPosition.getCenterY())));
            }
            if (biRole) {
                // Add catalyst segments
                final double top = Math.min(position.getY(), reactionPosition.getY()) - 5;
                segments.add(new SegmentImpl(position.getCenterX(), position.getY(), position.getCenterX(), top));
                segments.add(new SegmentImpl(position.getCenterX(), top, vRule + 50, top));
                segments.add(new SegmentImpl(vRule + 50, top, reactionPosition.getCenterX(), reactionPosition.getCenterY()));
                connector.setPointer(ConnectorType.CATALYST);
            } else {
                connector.setPointer(ConnectorType.INPUT);
            }
            // We expect to have stoichiometry only in input role
            for (Role role : entity.getRoles())
                if (role.getType() == INPUT) {
                    connector.setStoichiometry(getStoichiometry(segments, role));
                    break;
                }
        }
    }

    private ConnectorImpl createConnector(EntityGlyph entity) {
        final ConnectorImpl connector = new ConnectorImpl();
        final List<Segment> segments = new ArrayList<>();
        connector.setSegments(segments);
        entity.setConnector(connector);
        return connector;
    }


    private void outputConnectors(Layout layout) {
        final Position reactionPosition = layout.getReaction().getPosition();
        final double port = reactionPosition.getMaxX() + BACKBONE_LENGTH;
        final double mx = index.getOutputs().stream().map(Transformer::getBounds).mapToDouble(Position::getX).min().orElse(0);
        final double vRule = mx - MIN_SEGMENT - ARROW_SIZE;
        for (EntityGlyph entity : index.getOutputs()) {
            final ConnectorImpl connector = createConnector(entity);
            final List<Segment> segments = connector.getSegments();
            final Position position = entity.getPosition();
            segments.add(new SegmentImpl(
                    new CoordinateImpl(position.getX() - 4, position.getCenterY()),
                    new CoordinateImpl(vRule, position.getCenterY())));
            segments.add(new SegmentImpl(
                    new CoordinateImpl(vRule, position.getCenterY()),
                    new CoordinateImpl(port, reactionPosition.getCenterY())));
            // only one role expected: OUTPUT
            for (Role role : entity.getRoles()) {
                connector.setPointer(getConnectorType(role.getType()));
                connector.setStoichiometry(getStoichiometry(segments, role));
            }
        }
    }

    private ConnectorType getConnectorType(EntityRole type) {
        switch (type) {
            case OUTPUT:
                return ConnectorType.OUTPUT;
            case CATALYST:
                return ConnectorType.CATALYST;
            case NEGATIVE_REGULATOR:
                return ConnectorType.INHIBITOR;
            case POSITIVE_REGULATOR:
                return ConnectorType.ACTIVATOR;
            case INPUT:
            default:
                return ConnectorType.INPUT;
        }
    }

    private void catalystConnectors(Layout layout) {
        final Position reactionPosition = layout.getReaction().getPosition();
        final double port = reactionPosition.getCenterY();
        double my = index.getCatalysts().stream().map(Transformer::getBounds).mapToDouble(Position::getMaxY).max().orElse(0);
        final double hRule = my + MIN_SEGMENT;
        for (EntityGlyph entity : index.getCatalysts()) {
            final ConnectorImpl connector = createConnector(entity);
            final List<Segment> segments = connector.getSegments();
            final Position position = entity.getPosition();
            segments.add(new SegmentImpl(
                    new CoordinateImpl(position.getCenterX(), position.getMaxY()),
                    new CoordinateImpl(position.getCenterX(), hRule)));
            segments.add(new SegmentImpl(
                    new CoordinateImpl(position.getCenterX(), hRule),
                    new CoordinateImpl(reactionPosition.getCenterX(), port)));
            // only one role expected: CATALYST
            for (Role role : entity.getRoles()) {
                connector.setStoichiometry(getStoichiometry(segments, role));
                connector.setType(role.getType().name());
                connector.setPointer(getConnectorType(role.getType()));
            }
        }
    }

    private void regulatorConnectors(Layout layout) {
        final Position reactionPosition = layout.getReaction().getPosition();
        double my = index.getRegulators().stream().map(Transformer::getBounds).mapToDouble(Position::getY).min().orElse(0);
        final double hRule = my - MIN_SEGMENT;
        // we want to fit all catalysts in a semi-circumference, not using the corners
        final int sectors = index.getRegulators().size() + 1;
        // the semicircle is centered into the reaction, and its length (PI*radius) should be enough to fit all the
        // shapes without touching each other
        final double radius = reactionPosition.getHeight() / 2 + REGULATOR_SIZE * sectors / Math.PI;
        int i = 1;
        final ArrayList<EntityGlyph> regulators = new ArrayList<>(index.getRegulators());
        regulators.sort(Comparator.comparingDouble(v -> v.getPosition().getCenterX()));
        for (EntityGlyph entity : regulators) {
            final ConnectorImpl connector = createConnector(entity);
            final List<Segment> segments = connector.getSegments();
            final Position position = entity.getPosition();
            segments.add(new SegmentImpl(position.getCenterX(), position.getMaxY(), position.getCenterX(), hRule));
            final double x = reactionPosition.getCenterX() - radius * Math.cos(Math.PI * i / sectors);
            final double y = reactionPosition.getCenterY() + radius * Math.sin(Math.PI * i / sectors);
            segments.add(new SegmentImpl(position.getCenterX(), hRule, x, y));
            // Only one role expected (negative or positive)
            for (Role role : entity.getRoles()) {
                connector.setStoichiometry(getStoichiometry(segments, role));
                connector.setPointer(getConnectorType(role.getType()));
            }
            i++;
        }
    }

    /**
     * Creates the stoichiometry box in the first segment.
     */
    private Stoichiometry getStoichiometry(List<Segment> segments, Role role) {
        if (role.getStoichiometry() == 1)
            return new StoichiometryImpl(1, null);
        final Segment segment = segments.get(0);
        final Coordinate center = center(segment);
        final Coordinate a = new CoordinateImpl(center.getX() - 6, center.getY() - 6);
        final Coordinate b = new CoordinateImpl(center.getX() + 6, center.getY() + 6);
        final Shape shape = new BoxImpl(a, b, true, role.getStoichiometry().toString());
        return new StoichiometryImpl(role.getStoichiometry(), shape);
    }

    private Coordinate center(Segment segment) {
        return new CoordinateImpl(
                0.5 * (segment.getFrom().getX() + segment.getTo().getX()),
                0.5 * (segment.getFrom().getY() + segment.getTo().getY())
        );
    }

    private void layoutCompartments(Layout layout) {
        layoutCompartment(layout.getCompartmentRoot());
    }

    /**
     * Calculates the size of the compartments so each of them surrounds all of its contained glyphs and children.
     */
    private void layoutCompartment(CompartmentGlyph compartment) {
        for (CompartmentGlyph child : compartment.getChildren()) {
            layoutCompartment(child);
        }
        Position position = null;
        for (CompartmentGlyph child : compartment.getChildren()) {
            if (position == null) position = new Position(child.getPosition());
            else position.union(child.getPosition());
        }
        for (Glyph glyph : compartment.getContainedGlyphs()) {
            if (position == null) position = new Position(getBounds(glyph));
            else position.union(getBounds(glyph));
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

    private void computeDimension(Layout layout) {
        Position position = null;
        for (CompartmentGlyph compartment : layout.getCompartments()) {
            if (position == null) position = new Position(compartment.getPosition());
            else position.union(compartment.getPosition());
        }
        for (EntityGlyph entity : layout.getEntities()) {
            if (position == null) position = new Position(Transformer.getBounds(entity));
            else position.union(Transformer.getBounds(entity));
        }
        if (position == null) position = new Position(Transformer.getBounds(layout.getReaction()));
        else position.union(Transformer.getBounds(layout.getReaction()));
        layout.getPosition().set(position);
    }

    private void moveToOrigin(Layout layout) {
        final double dx = -layout.getPosition().getX();
        final double dy = -layout.getPosition().getY();
        final Coordinate delta = new CoordinateImpl(dx, dy);
        layout.getPosition().move(dx, dy);
        move(layout.getCompartmentRoot(), delta, true);
    }

    /**
     * This operation should be called in the last steps, to avoid being exported to a Diagram object.
     */
    private void removeExtracellular(Layout layout) {
        layout.getCompartments().remove(layout.getCompartmentRoot());
    }

    private class Tile {
        private final List<Glyph> glyphs;
        private final Position bounds;
        private int row;
        private int column;

        private Tile(List<Glyph> glyphs, Position bounds) {
            this.glyphs = glyphs;
            this.bounds = bounds;
        }

        private List<Glyph> getGlyphs() {
            return glyphs;
        }

        private Position getBounds() {
            return bounds;
        }
    }
}