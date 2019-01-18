package org.reactome.server.tools.reaction.exporter.ontology;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toMap;
import static org.reactome.server.tools.reaction.exporter.ontology.GoTerm.Directionality.INCOMING;
import static org.reactome.server.tools.reaction.exporter.ontology.GoTerm.Directionality.OUTGOING;
import static org.reactome.server.tools.reaction.exporter.ontology.RelationshipType.*;

/**
 * @author Pascual Lorente (plorente@ebi.ac.uk)
 */
public class GoTreeFactory {

    private static final String ROOT_ID = "GO:0005576";
    private static final List<RelationshipType> RELS = Arrays.asList(surrounded_by, component_of, part_of, is_a);
    private static Map<String, GoTerm> terms = GoParser.readCompressed().values().stream()
            .collect(Collectors.toMap(GoTerm::getId, Function.identity()));

    /**
     * Creates a subtree from the components tree that contains the minimum number of terms such as all of the terms in
     * <em>ids</em> are connected to the tree root.
     *
     * @param ids a list of GO accession (without GO: prefix)
     * @return a copy of the components root, with a smaller copy of the tree containing <em>ids</em>
     */
    public static GoTerm getTree(List<String> ids) {
        // Create a graph with a copy of the nodes
        final Map<String, GoTerm> subTree = ids.stream()
                .peek(id -> {
                    if (!terms.containsKey(id)) System.err.println(id);
                })
                .filter(id -> !id.equals(ROOT_ID))
                .filter(id -> terms.containsKey(id))
                .collect(toMap(Function.identity(), id -> terms.get(id).copy()));

        // extracellular region as root
        final GoTerm root = terms.get(ROOT_ID).copy();
        subTree.values().forEach(goTerm -> {
            // climb parents til reach one of the elements in tree
            // add myself as children
            // if not, add as surrounded by root
            if (!findParent(goTerm, terms.get(goTerm.getId()), subTree)) {
                goTerm.createRelationship(OUTGOING, surrounded_by, root);
            }
        });
        subTree.put(root.getId(), root);
        return root;
    }

    /**
     * Creates a subtree from the GO tree that contains the terms in <em>ids</em> are connected and those in-between
     * them.
     *
     * @param ids a list of GO accession (without GO: prefix)
     * @return a copy of the components root, with a smaller copy of the tree containing <em>ids</em>
     */
    public static GoTerm getTreeWithIntermediateNodes(List<String> ids) {
        final Map<String, GoTerm> index = new HashMap<>();
        final List<GoTerm> terms = ids.stream().map(id -> new GoTerm(GoTreeFactory.terms.get(id))).collect(Collectors.toList());
        for (final GoTerm term : terms) addHierarchy(term, index);
        // Remove upper bound compartments
        final GoTerm root = index.get(ROOT_ID);
        removeUpperCompartments(ids, root);
        return root;
    }

    private static void removeUpperCompartments(List<String> ids, GoTerm root) {
        GoTerm outer = root;
        while (outer.getIncomingTerms().size() == 1 && !ids.contains(outer.getId()))
            outer = outer.getIncomingTerms().iterator().next();
        if (outer == root) return;
        GoTerm r = root;
        GoTerm c = root.getIncomingTerms().iterator().next();
        while (c != outer) {
            c.removeRelationship(r);
            r = c;
            c = c.getIncomingTerms().iterator().next();
        }
        outer.createRelationship(OUTGOING, surrounded_by, root);
    }

    private static void removeLogicalRelationships(GoTerm root) {
        for (final RelationshipType type : RELS) {
            final Collection<GoTerm> dispose = new ArrayList<>();
            for (final GoTerm term : root.getRelationships(INCOMING, type)) {
                final Collection<GoTerm> children = new ArrayList<>(term.getRelationships(INCOMING, RelationshipType.is_a));
                if (children.size() > 0) {
                    for (final GoTerm child : children) root.createRelationship(INCOMING, type, child);
                    for (final GoTerm child : children) child.removeRelationship(term);
                    dispose.add(term);
                }
            }
            for (final GoTerm term : dispose) term.removeRelationship(root);
        }
        for (final GoTerm child : root.getIncomingTerms()) removeLogicalRelationships(child);
    }


    private static void addHierarchy(GoTerm term, Map<String, GoTerm> index) {
        // This node has already been visited
        if (index.containsKey(term.getId())) return;
        // Mark as visited
        index.putIfAbsent(term.getId(), term);
        final GoTerm masterTerm = terms.get(term.getId());
        // We will use the first parent found, ordered by rels
        for (final RelationshipType type : RELS) {
            final Collection<GoTerm> relationships = masterTerm.getRelationships(OUTGOING, type);
            if (relationships.isEmpty()) continue;
            // We use the first one
            final GoTerm masterParent = relationships.iterator().next();
            // Create a copy and connect
            final GoTerm parent = index.getOrDefault(masterParent.getId(), new GoTerm(masterParent));
            term.createRelationship(OUTGOING, type, parent);
            // final boolean add = true;
            addHierarchy(parent, index);
            return;
        }
        // If nothing was found, let's go for the root
        // We use the first one
        final GoTerm masterParent = terms.get(ROOT_ID);
        // Create a copy and connect
        final GoTerm parent = index.computeIfAbsent(masterParent.getId(), id -> new GoTerm(masterParent));
        if (term == parent) return;
        term.createRelationship(OUTGOING, surrounded_by, parent);
    }

    private static boolean findParent(GoTerm goTerm, GoTerm graphTerm, Map<String, GoTerm> tree) {
        // Is any of the elements on the map my parent?
        for (RelationshipType rel : RELS) {
            final Set<GoTerm> relationships = graphTerm.getRelationships(OUTGOING, rel);
            for (GoTerm term : relationships) {
                if (tree.containsKey(term.getId())) {
                    goTerm.createRelationship(OUTGOING, rel, tree.get(term.getId()));
                    return true;
                }
            }
        }
        // Has any of the ancestors of graphTerm a parent in the map?
        for (RelationshipType rel : RELS) {
            final Set<GoTerm> relationships = graphTerm.getRelationships(OUTGOING, rel);
            for (GoTerm term : relationships)
                if (findParent(goTerm, term, tree)) return true;
        }
        return false;
    }

    public static GoTerm getRoot() {
        return terms.get(ROOT_ID);
    }
}
