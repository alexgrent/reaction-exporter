package org.reactome.server.tools.reaction.exporter.layout.model;

import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.tools.reaction.exporter.layout.common.RenderableClass;

/**
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 * @author Pascual Lorente (plorente@ebi.ac.uk)
 */
public class ReactionGlyph extends AbstractGlyph {

    private String stId;
    private String name;

    ReactionGlyph(ReactionLikeEvent rle) {
        super();
        stId = rle.getStId();
        name = rle.getDisplayName();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public RenderableClass getRenderableClass() {
        return RenderableClass.REACTION;
    }

    @Override
    public String toString() {
        return "ReactionGlyph{stId='" + stId + "'}";
    }
}
