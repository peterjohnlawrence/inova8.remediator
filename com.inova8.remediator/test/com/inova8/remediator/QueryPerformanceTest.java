/**
 * 
 */
package com.inova8.remediator;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.apache.jena.atlas.logging.Log;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import com.hp.hpl.jena.ontology.OntModel;
import com.hp.hpl.jena.ontology.OntModelSpec;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.query.ResultSetFormatter;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.sparql.algebra.Op;
import com.hp.hpl.jena.sparql.algebra.OpAsQuery;
import com.inova8.remediator.FederatedQuery;
import com.inova8.remediator.QueryPlan;
import com.inova8.remediator.Void;
import com.inova8.remediator.Workspace;

/**
 * @author PeterL
 * 
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class QueryPerformanceTest {

	static String readFile(String path, Charset encoding) throws IOException {
		byte[] encoded = Files.readAllBytes(Paths.get(path));
		return encoding.decode(ByteBuffer.wrap(encoded)).toString();
	}

	public QueryPerformanceTest() {
		super();
	}

	String URL;
	String ontologyModelFile;
	String queryString;
	String rewriteString;
	String planString;
	String queryResultString;
	String queryFolder;
	String path;

	OntModelSpec ontModelSpec;
	OntModel ontModel;
	Void queryVoid;
	Void queryVoid1;
	Workspace workspace;

	/**
	 * @throws java.lang.Exception
	 */
	@Before
	public void setUp() throws Exception {
		path = "./resources/";
		queryFolder = "Q1";

		ontologyModelFile = readFile(path + queryFolder + "/model.txt",
				Charset.defaultCharset());

		workspace = new Workspace(new File(path + queryFolder), true);
		URL = "http://inova8.com/people/void";
		queryVoid = new Void(workspace, URL, true);
	}

	/**
	 * @throws java.lang.Exception
	 */
	@After
	public void tearDown() throws Exception {
	}

	@Test
	public final void testQueryPerformance() throws Exception {
		String rewrittenQuery =queryTranslate(6); 
		
		for (int i = 0; i <= 10; i++) {
			queryExecute(rewrittenQuery,6);
		}
	}
	public final String queryTranslate(Integer testNumber) throws Exception {
		queryString = readFile(path + queryFolder + "/test" + testNumber + "/query.txt",
				Charset.defaultCharset());
		rewriteString = readFile(path + queryFolder + "/test"+ testNumber + "/rewrite.txt",
				Charset.defaultCharset());
		planString = readFile(path + queryFolder + "/test"+ testNumber + "/plan.txt",
				Charset.defaultCharset());

		FederatedQuery federatedQuery = new FederatedQuery(queryString);
		Op rewrittenOperations = federatedQuery.rewrite(queryVoid, true);
		QueryPlan queryPlan = federatedQuery.getQueryPlan();
//		assertEquals(queryPlan.toString(), planString.replaceAll("\\s+",
//				""), queryPlan.toString().replaceAll("\\s+", ""));	
		
		Query rewrittenQuery = OpAsQuery.asQuery(rewrittenOperations);
//
//		assertEquals(rewrittenQuery.toString(), rewriteString.replaceAll("\\s+",
//				""), rewrittenQuery.toString().replaceAll("\\s+", ""));
		return rewrittenQuery.toString();
	}
	private void queryExecute(String rewriteString,Integer testNumber) throws Exception {
		queryResultString = readFile(path + queryFolder + "/test"+ testNumber + "/queryresult.txt",
				Charset.defaultCharset());
        OntModel model =  ModelFactory.createOntologyModel(); 
		Query query = QueryFactory.create(rewriteString);
		QueryExecution qexec = QueryExecutionFactory.create(query,model);
		try {
			ResultSet results = qexec.execSelect();
			String resultString = ResultSetFormatter.asText(results);
//			assertEquals(resultString.toString(), queryResultString.replaceAll("\\s+",
//					""), resultString.toString().replaceAll("\\s+", ""));
		} catch (Exception e) {
			Log.debug(Void.class, "Failed query");
			Log.debug(Void.class, e.getStackTrace().toString());
			e.printStackTrace();
		} finally {
			qexec.close();
		}
	}
}