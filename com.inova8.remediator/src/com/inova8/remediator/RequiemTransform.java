package com.inova8.remediator;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.jena.atlas.logging.Log;
import org.apache.xerces.util.URI;

import org.apache.xerces.util.URI.MalformedURIException;

import org.oxford.comlab.requiem.parser.ELHIOParser;
import org.oxford.comlab.requiem.rewriter.Clause;
import org.oxford.comlab.requiem.rewriter.FunctionalTerm;
import org.oxford.comlab.requiem.rewriter.Rewriter;
import org.oxford.comlab.requiem.rewriter.Term;
import org.oxford.comlab.requiem.rewriter.TermFactory;
import org.oxford.comlab.requiem.rewriter.Variable;
import org.semanticweb.owl.model.OWLOntologyURIMapper;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.NodeFactory;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.sparql.algebra.Op;
import com.hp.hpl.jena.sparql.algebra.OpVars;
import com.hp.hpl.jena.sparql.algebra.TransformCopy;
import com.hp.hpl.jena.sparql.algebra.op.OpBGP;
import com.hp.hpl.jena.sparql.algebra.op.OpSequence;
import com.hp.hpl.jena.sparql.algebra.op.OpService;
import com.hp.hpl.jena.sparql.algebra.op.OpUnion;
import com.hp.hpl.jena.sparql.core.BasicPattern;
import com.hp.hpl.jena.sparql.core.Var;

public class RequiemTransform extends TransformCopy {
	final private QueryVars queryVars;
	private ByteArrayInputStream ontologyInputStream = null;
	private OWLOntologyURIMapper owlOntologyURIMapper = null;

	private static TermFactory termFactory = new TermFactory();
	private static final ELHIOParser parser = new ELHIOParser(termFactory);
	private static final Rewriter rewriter = new Rewriter();

	private ArrayList<Clause> clauses = new ArrayList<Clause>();
	private ArrayList<OpBGP> ops = new ArrayList<OpBGP>();
	private Datasets datasets;
	private Linksets linksets;
	private Void voidModel;
	private QueryPlan queryPlan;


	private Boolean optimize;
	//HashMap<DatasetQueryVarLinkset, LinksetOpService> linksetOpServices = new HashMap<DatasetQueryVarLinkset, LinksetOpService>();
	DatasetQueryVarLinksets linksetOpServices = new DatasetQueryVarLinksets ();

	RequiemTransform(QueryVars queryVars, Void voidModel, Boolean optimize) {
		super();
		this.queryVars = queryVars;
		// TODO replace variables with voidModel references
		this.voidModel = voidModel;
		this.optimize = optimize;
		this.ontologyInputStream = voidModel.writeVocabularyModel();
		this.owlOntologyURIMapper = voidModel.getWorkspace().getOWLOntologyURIMapper();
		this.datasets = voidModel.getDatasets();
		this.linksets = voidModel.getLinksets();
	}

	private Clause BGPToClause(OpBGP opBGP) {
		Term[] bAtom = new Term[opBGP.getPattern().size()];
		int index = 0;
		for (Triple triple : opBGP.getPattern().getList()) {
			bAtom[index] = tripleToTerm(triple);
			index++;
		}
		Term hAtom = BGPToTerm(opBGP);
		return new Clause(bAtom, hAtom);
	}

	private Term BGPToTerm(OpBGP opBGP) {
		new OpVars();
		Set<Var> variables = OpVars.visibleVars(opBGP);
		Term[] vAtom = new Term[variables.size()];
		int index = 0;
		for (Var variable : variables) {
			vAtom[index] = termFactory.getVariable(queryVars.indexOf(variable));
			index++;
		}
		return termFactory.getFunctionalTerm("Q", vAtom);
	}

	private OpBGP clauseToBGP(Dataset dataset, Clause clause) {
		return new OpBGP(clauseToPattern(dataset, clause));
	}

	private BasicPattern clauseToPattern(Dataset dataset, Clause clause) {
		// new ElementTriplesBlock();
		BasicPattern pattern = new BasicPattern();
		for (Term term : clause.getBody()) {
			pattern.add(termToTriple(dataset, term));
		}
		return pattern;
	}

	public ArrayList<Clause> getClauses() {
		return clauses;
	}

	public ArrayList<Dataset> getDatasets() {
		return datasets;
	}

	public ArrayList<OpBGP> getOps() {
		return ops;
	}
	protected QueryPlan getQueryPlan() {
		return queryPlan;
	}
	private Term nodeToTerm(Node node) {
		if (node.isLiteral()) {
			return termFactory.getConstant(node.getLiteral().toString());
		} else if (node.isURI()) {
			// return termFactory.getConstant(prefixes.qnameFor(node.getURI()));
			return termFactory.getConstant(node.getURI().toString());
		} else if (node.isVariable()) {
			return termFactory.getVariable(queryVars.indexOf((Var) node));
		} else if (node.isBlank()) {
			// TODO handle blank nodes in BGP
			return null;
		} else {
			return null;
		}
	}

	private ArrayList<Clause> rewrite(Clause cl) throws Exception {
		ArrayList<Clause> original = new ArrayList<Clause>();
		original = voidModel.getConjunctiveClauses();
		original.add(cl);
		// N (Naive: no optimizations), F (Full: forward/query subsumption and
		// dependency graph pruning), or G (Greedy: Full plus greedy unfolding).
		return rewriter.rewrite(original, "G");
	}

	public void setClauses(ArrayList<Clause> clauses) {
		this.clauses = clauses;
	}

	public void setOps(ArrayList<OpBGP> ops) {
		this.ops = ops;
	}

	private Node termToNode(Dataset dataset, Term term) {
		if (term instanceof FunctionalTerm) {
			try {
				URI uri = new URI(term.getName());
				return NodeFactory.createURI(uri.toString());
			} catch (MalformedURIException e) {
				return NodeFactory.createLiteral(term.toString());
			}
		} else if (term instanceof Variable) {
			QueryVar var = queryVars.get(term.getMinVariableIndex());
			return NodeFactory.createVariable(var.getLinkedName(dataset));
		} else {
			return null;
		}
	}

	private Triple termToTriple(Dataset dataset, Term term) {
		if (term.getArity() == 1) {
			Node clas = NodeFactory.createURI(term.getName());
			Node object = termToNode(dataset, term.getArgument(0));
			return Triple
					.create(object, NodeFactory.createURI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"), clas);
		} else if (term.getArity() == 2) {
			Node pred = NodeFactory.createURI(term.getName());
			Node subj = termToNode(dataset, term.getArgument(0));
			Node obj = termToNode(dataset, term.getArgument(1));
			return Triple.create(subj, pred, obj);
		} else {
			return null;
		}
	}

	@Override
	public Op transform(OpBGP opBGP) {

		rewriteBGP(opBGP);

		voidModel.InferVariableClasses(queryVars);

		queryVars.locateDatasetClauses(datasets);

		createLinksetQueryClauses();

		// TODO Should be conditional on optimize &&
		// voidModel.getPartitionStatisticsAvailable()
		createOptimalQueryPlan();

		createDatasetQueryClauses();

		return createOpSequence();
	}

	private OpSequence createOpSequence() {
		OpSequence opSequence = OpSequence.create();

		DatasetQueryVarLinksets initialLinksetOpServices = new DatasetQueryVarLinksets();
		initialLinksetOpServices = (DatasetQueryVarLinksets) linksetOpServices.clone();
		DatasetQueryVarLinksets pendingLinksetOpServices = new DatasetQueryVarLinksets();

		for (MergedJoin mergedJoin : queryPlan) {
			prependLinkQueries(opSequence, mergedJoin, pendingLinksetOpServices);
			insertMergedJoin(opSequence, mergedJoin);
			appendLinkQueries(opSequence, mergedJoin, pendingLinksetOpServices, initialLinksetOpServices);
		}
		return opSequence;
	}

	private void prependLinkQueries(OpSequence opSequence, MergedJoin mergedJoin,
			DatasetQueryVarLinksets pendingLinksetOpServices) {
		for (QueryClause queryClause : mergedJoin.getQueryClauses()) {
			for (QueryVar queryVar : queryClause.getClauseVariables(queryVars)) {
				if (queryVar.isLinked()) {
					for (DatasetQueryVarLinkset datasetQueryVarLinkset : queryVar.getDatasetQueryVars().keySet()) {
						if (pendingLinksetOpServices.contains(datasetQueryVarLinkset)
								&& datasetQueryVarLinkset.getDataset() == queryClause.getDataset()) {
							opSequence.add(queryVar.getDatasetQueryVars().get(datasetQueryVarLinkset).getLinksetOpService()
									.getOpService());
							DatasetQueryVarLinkset otherDatasetQueryVarLinkset = queryVar.getDatasetQueryVars().get(datasetQueryVarLinkset)
									.getOtherDatasetQueryVarLinkset();
							pendingLinksetOpServices.remove(datasetQueryVarLinkset);
							pendingLinksetOpServices.remove(otherDatasetQueryVarLinkset);
						}
					}
				}
			}
		}
	}

	private void insertMergedJoin(OpSequence opSequence, MergedJoin mergedJoin) {
		Iterator<QueryClause> queryClauseIterator = mergedJoin.getQueryClauses().iterator();
		if (mergedJoin.getQueryClauses().size() > 1) {
			QueryClause priorQueryClause = queryClauseIterator.next();
			Op priorOp = priorQueryClause.getOpService();
			OpUnion priorOpUnion = null;
			QueryClause nextQueryClause;
			while (queryClauseIterator.hasNext()) {
				nextQueryClause = queryClauseIterator.next();
				priorOpUnion = new OpUnion(priorOp, nextQueryClause.getOpService());
				priorOp = priorOpUnion;
			}
			opSequence.add(priorOpUnion);
		} else {
			opSequence.add(queryClauseIterator.next().getOpService());
		}
	}

	private void appendLinkQueries(OpSequence opSequence, MergedJoin mergedJoin,
			DatasetQueryVarLinksets pendingLinksetOpServices,
			DatasetQueryVarLinksets initialLinksetOpServices) {
		for (QueryClause queryClause : mergedJoin.getQueryClauses()) {
			for (QueryVar queryVar : queryClause.getClauseVariables(queryVars)) {
				if (queryVar.isLinked()) {
					for (DatasetQueryVarLinkset datasetQueryVarLinkset : queryVar.getDatasetQueryVars().keySet()) {
						if (initialLinksetOpServices.contains(datasetQueryVarLinkset)
								&& datasetQueryVarLinkset.getDataset() == queryClause.getDataset()) {
							DatasetQueryVarLinkset otherDatasetQueryVarLinkset = queryVar.getDatasetQueryVars().get(datasetQueryVarLinkset)
									.getOtherDatasetQueryVarLinkset();
							//pendingLinksetOpServices.put(otherDatasetQueryVarLinkset,
							//		initialLinksetOpServices.get(datasetQueryVarLinkset));
							pendingLinksetOpServices.add(otherDatasetQueryVarLinkset);
							initialLinksetOpServices.remove(datasetQueryVarLinkset);
							initialLinksetOpServices.remove(otherDatasetQueryVarLinkset);
						}
					}
				}
			}
		}
	}
	

	private void rewriteBGP(OpBGP opBGP) {
		Clause clause = BGPToClause(opBGP);
		try {
			clauses = rewrite(clause);
			// clauses = rewriteDlite(clause);
		} catch (Exception e) {
			Log.debug(RequiemTransform.class, "Failed to rewrite clause " + clause.toString());
			Log.debug(RequiemTransform.class, e.getStackTrace().toString());
			e.printStackTrace();
		}
		for (Clause cl : clauses) {
			for (Dataset dataset : datasets) {
				dataset.addClause(cl);
			}
		}
	}

	private void createLinksetQueryClauses() {
		for (Linkset linkset : linksets) {
			if (linkset.supportsInstanceMapping()) {
				QueryVars variablesOfSubjectClass = queryVars.locateVariablesOfClass(linkset.getSubjectsClass());
				for (QueryVar linkQueryVariable : variablesOfSubjectClass) {
					// TODO need to remove the need to pass through
					// queryVariables.
					QueryClauses subjectQueryClauses = linkset.getSubjectsDataset().getClauseVariables(queryVars,
							linkQueryVariable);
					// These dataset queryClause variables could be linked to
					// another dataset queryClause variable
					// So now for each we need to find the 'other' end of the
					// linkset and find any clauses in the objectsDataset that
					// share the same variable.
					// (Does it have to conform to objectsClass?)
					QueryClauses objectQueryClauses = linkset.getObjectsDataset().getClauseVariables(queryVars,
							linkQueryVariable);

					// Now we have both ends of the link that we can create
					// between clause variables, as long as not the same
					// dataset
					// subjectQueryClauses/subjectQueryVariable sameAs
					// objectQueryClauses/objectQueryVariable

					// Generate the SPARQL algebra
					for (QueryClause subjectQueryClause : subjectQueryClauses) {
						for (QueryClause objectQueryClause : objectQueryClauses) {
							linkQueryVariable.setLinked();
							Node linksetNode = NodeFactory.createURI(linkset.getSparqlEndPoint().toString());
							BasicPattern pattern = new BasicPattern();

							Node pred = NodeFactory.createURI(linkset.getLinkPredicate().toString());
							pattern.add(Triple.create(linkQueryVariable.getLinkedVar(linkset.getSubjectsDataset()), pred,linkQueryVariable.getLinkedVar(linkset.getObjectsDataset())));

							DatasetQueryVarLinkset subjectDatasetQueryVarLinkset = linkset.getSubjectsDataset().getDatasetQueryVarLinkset(
									linkQueryVariable,linkset);
							DatasetQueryVarLinkset objectDatasetQueryVarLinkset = linkset.getObjectsDataset().getDatasetQueryVarLinkset(
									linkQueryVariable,linkset);
							LinksetOpService linksetOpService = new LinksetOpService(linkset, new OpService(
									linksetNode, new OpBGP(pattern), false));

							linkQueryVariable.addDatasetQueryVar(subjectDatasetQueryVarLinkset, linksetOpService,
									objectDatasetQueryVarLinkset);

							linkQueryVariable.addDatasetQueryVar(objectDatasetQueryVarLinkset, linksetOpService,
									subjectDatasetQueryVarLinkset);

							//TODO
							//linksetOpServices.put(subjectDatasetQueryVarLinkset, linksetOpService);
							//linksetOpServices.put(objectDatasetQueryVarLinkset, linksetOpService);

							linksetOpServices.add(subjectDatasetQueryVarLinkset);
							linksetOpServices.add(objectDatasetQueryVarLinkset);

						}
					}
				}
			}
		}
	}

	private void createDatasetQueryClauses() {
		for (Dataset dataset : datasets) {
			Node datasetNode = NodeFactory.createURI(dataset.getSparqlEndPoint().toString());
			for (QueryClause datasetClause : dataset.getQueryClauses()) {
				datasetClause.setOpService(new OpService(datasetNode, new OpBGP(clauseToPattern(dataset,
						datasetClause)), false));
			}
		}
	}

	/**
	 * 1. Sort the required variables according to the number of queries within which they appear. 
	 * 2. Select the queries associated with the variable with the fewest number of queries. 
	 * 3. Create a merge join between the queries within which the chosen variable appears. 
	 * 4. Adjust the sorting of the remaining variables by removing the queries that
	 * have just been added to the query plan. 
	 * 5. Repeat 2-4 until all variables have been resolved.
	 */
	private void createOptimalQueryPlan() {
		// The list of clauses that have been resolved and added to the query
		//
		queryPlan = new QueryPlan();

		QueryVars resolvedQueryVars = new QueryVars();
		QueryClauses resolvedClauses = new QueryClauses();
		HashSet<QueryVar> lowestCostQueryVars;
		// TODO queryVariables.locateDatasetClauses(datasets);
		// Keep on iterating through list of query variables until none left to
		// resolve.
		while (resolvedQueryVars.size() < queryVars.size()) {
			// Find the variable(s) involved in the lowest cost/count of clauses
			Double lowestCost = null;
			lowestCostQueryVars = new HashSet<QueryVar>();
			for (QueryVar queryVar : queryVars) {
				if (!resolvedQueryVars.contains(queryVar)) {
					Double variableCost;
					if (optimize) {
						variableCost = queryVar.clauseCost(resolvedClauses, resolvedQueryVars, queryVars);
					} else {
						variableCost = (double) (queryVar.clauseCount(resolvedClauses, resolvedQueryVars));
					}
					if (lowestCost == null) {
						// First pass so initialize lowestCost
						lowestCost = variableCost;
						lowestCostQueryVars = new HashSet<QueryVar>();
						lowestCostQueryVars.add(queryVar);
					} else if (variableCost == null) {
						// Variable has been implicitly eliminated by selection
						// of all its clauses when selecting other variables
						resolvedQueryVars.add(queryVar);
					} else {
						if (variableCost < lowestCost) {
							// New lowest candidate found so replace current
							// selection
							lowestCost = variableCost;
							lowestCostQueryVars = new HashSet<QueryVar>();
							lowestCostQueryVars.add(queryVar);
						} else if (variableCost.equals(lowestCost)) {
							// Exactly matches candidate so add to the list
							lowestCostQueryVars.add(queryVar);
						} else {
							// do nothing
						}
					}
				}
			}
			MergedJoin mergedJoin = new MergedJoin();
			for (QueryVar queryVar : lowestCostQueryVars) {
				for (QueryClause queryClause : queryVar.getClauses()) {
					// Add any unresolved clauses to merged join
					if (!resolvedClauses.contains(queryClause) && !mergedJoin.contains(queryClause))
						mergedJoin.add(queryClause);

				}
				mergedJoin.add(queryVar);
				// Add all resolved clauses so they are not searched again
				resolvedClauses.addAll(queryVar.getClauses());
			}
			mergedJoin.setOptimizerCost(lowestCost);
			// Add mergedJoin to query plan if there is anything to add
			if (mergedJoin.size() > 0)
				queryPlan.add(mergedJoin);
			// Add variables to resolved list
			resolvedQueryVars.addAll(lowestCostQueryVars);
		}
	}

	private Term tripleToTerm(Triple triple) {
		Node pred = triple.getPredicate();
		Node subj = triple.getSubject();
		Node obj = triple.getObject();
		try {
			if (pred.getURI().toString().equals("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")) {
				return termFactory.getFunctionalTerm(obj.getURI().toString(), nodeToTerm(subj));
			} else {
				return termFactory.getFunctionalTerm(pred.getURI().toString(), nodeToTerm(subj), nodeToTerm(obj));
			}
		} catch (UnsupportedOperationException e) {
			throw e;
		}
	}
}