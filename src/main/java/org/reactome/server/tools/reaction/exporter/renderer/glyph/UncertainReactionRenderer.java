package org.reactome.server.tools.reaction.exporter.renderer.glyph;

import org.reactome.server.tools.reaction.exporter.layout.model.ReactionGlyph;
import org.reactome.server.tools.reaction.exporter.renderer.utils.ShapeFactory;

import java.awt.*;

public class UncertainReactionRenderer extends ReactionRenderer {

    @Override
    protected Shape getShape(ReactionGlyph entity) {
        return ShapeFactory.rectangle(entity.getPosition());
    }

    @Override
    protected String getText() {
        return "?";
    }
}
