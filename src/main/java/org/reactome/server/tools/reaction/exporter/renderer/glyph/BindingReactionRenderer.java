package org.reactome.server.tools.reaction.exporter.renderer.glyph;

import org.reactome.server.tools.reaction.exporter.layout.model.ReactionGlyph;
import org.reactome.server.tools.reaction.exporter.renderer.profile.DiagramProfile;
import org.reactome.server.tools.reaction.exporter.renderer.utils.ShapeFactory;

import java.awt.*;

public class BindingReactionRenderer extends ReactionRenderer {

    @Override
    protected Shape getShape(ReactionGlyph entity) {
        return ShapeFactory.getOval(entity.getPosition());
    }

    @Override
    protected String getText() {
        return "";
    }

    @Override
    protected Paint getFillColor(ReactionGlyph entity, DiagramProfile profile) {
        return profile.getReaction().getStroke();
    }
}
