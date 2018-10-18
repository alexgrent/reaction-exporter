package org.reactome.server.tools.reaction.exporter.layout.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.reactome.server.graph.domain.model.BlackBoxEvent;
import org.reactome.server.graph.domain.model.Compartment;
import org.reactome.server.graph.domain.model.ReactionLikeEvent;
import org.reactome.server.tools.reaction.exporter.goontology.GoTerm;
import org.reactome.server.tools.reaction.exporter.goontology.GoTreeFactory;
import org.reactome.server.tools.reaction.exporter.layout.common.Position;
import org.reactome.server.tools.reaction.exporter.layout.common.RenderableClass;

import java.util.*;

/**
 * Main class aggregating the compartment and the reaction with all its participants
 *
 * @author Antonio Fabregat (fabregat@ebi.ac.uk)
 * @author Pascual Lorente (plorente@ebi.ac.uk)
 */
public class Layout implements HasPosition {

    private Position position = new Position();

    private ReactionGlyph reactionGlyph;

    private Collection<EntityGlyph> entities;

    private CompartmentGlyph compartmentRoot;

    private Map<String, CompartmentGlyph> compartments = new HashMap<>();

    private ReactionLikeEvent rle = null;
    private Integer delta = null;

    public void add(EntityGlyph entityGlyph) {
        entities.add(entityGlyph);
    }

    public Collection<CompartmentGlyph> getCompartments() {
        return compartments.values();
    }

    @JsonIgnore
    public CompartmentGlyph getCompartmentRoot() {
        return compartmentRoot;
    }

    public Collection<EntityGlyph> getEntities() {
        return entities;
    }

    public ReactionGlyph getReaction() {
        return reactionGlyph;
    }

    @Override
    public Position getPosition() {
        return position;
    }

    // This setter is called automatically by the graph-core marshaller
    @SuppressWarnings("unused")
    public void setReactionLikeEvent(ReactionLikeEvent rle) {
        this.rle = rle;
        reactionGlyph = new ReactionGlyph(rle);

        //noinspection LoopStatementThatDoesntLoop
        for (Compartment compartment : rle.getCompartment()) {
            String acc = compartment.getAccession();
            CompartmentGlyph cg = compartments.computeIfAbsent(acc, i -> new CompartmentGlyph(compartment));
            cg.addGlyph(reactionGlyph);
            break; //We only want to assign the reaction to the first compartment in the list
        }

        setReactionGlyphRenderableClass();
    }

    // This setter is called automatically by the graph-core marshaller
    @SuppressWarnings("unused")
    public void setParticipants(Collection<EntityGlyph> participants) {
        Map<String, EntityGlyph> entities = new HashMap<>();

        for (EntityGlyph participant : participants) {
            EntityGlyph g = entities.get(participant.getStId());
            if (g != null) {
                //In this case participant ONLY has one role
                g.addRole(participant.getRoles().iterator().next());
            } else {
                entities.put(participant.getStId(), participant);
            }
        }

        delta = 0;
        for (EntityGlyph participant : entities.values()) {
            //noinspection LoopStatementThatDoesntLoop
            for (Compartment compartment : participant.getCompartments()) {
                String acc = compartment.getAccession();
                CompartmentGlyph cg = compartments.computeIfAbsent(acc, i -> new CompartmentGlyph(compartment));
                cg.addGlyph(participant);
                break; //We only want to assign the participant to the first compartment in the list
            }

            //Taking advantage of the iteration of the participants to calculate the differential 'delta' between the
            //number of inputs and outputs that will later on be used to set the renderable class of the reaction
            for (Role role : participant.getRoles()) {
                switch (role.getType()) {
                    case INPUT:     delta += role.getStoichiometry();   break;
                    case OUTPUT:    delta -= role.getStoichiometry();   break;
                }
            }
        }

        List<String> compartments = new ArrayList<>();
        for (CompartmentGlyph compartment : this.compartments.values()) {
            compartments.add("GO:" + compartment.getAccession());
        }

        GoTerm treeRoot = GoTreeFactory.getTree(compartments);
        compartmentRoot = this.compartments.computeIfAbsent(treeRoot.getAccession(), a -> new CompartmentGlyph(treeRoot));


        buildCompartmentHierarchy(compartmentRoot, treeRoot);
        setReactionGlyphRenderableClass();

        this.entities = new HashSet<>(entities.values());
    }

    private void buildCompartmentHierarchy(CompartmentGlyph cg, GoTerm term) {
        for (GoTerm goTerm : term.getChildren()) {
            CompartmentGlyph aux = compartments.computeIfAbsent(goTerm.getAccession(), a -> new CompartmentGlyph(goTerm));
            cg.addChild(aux);
            aux.setParent(cg);
            buildCompartmentHierarchy(aux, goTerm);
        }
    }

    private void setReactionGlyphRenderableClass() {
        if (rle == null || delta == null) return;
        RenderableClass rc;
        if (rle instanceof BlackBoxEvent) rc = RenderableClass.OMITTED_REACTION;
        else if (delta > 0) rc = RenderableClass.BINDING_REACTION;
        else if (delta < 0) rc = RenderableClass.DISSOCIATION_REACTION;
        else rc = RenderableClass.TRANSFORMATION_REACTION;
        reactionGlyph.setRenderableClass(rc);
    }
}
