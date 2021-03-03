package org.versly.rest.wsdoc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;

import org.raml.model.Action;
import org.raml.model.ActionType;
import org.raml.model.DocumentationItem;
import org.raml.model.Raml;
import org.raml.model.Resource;
import org.raml.model.SecurityReference;
import org.raml.model.Template;
import org.raml.model.parameter.QueryParameter;
import org.raml.model.parameter.UriParameter;
import org.raml.parser.visitor.RamlDocumentBuilder;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;
import org.versly.rest.wsdoc.impl.RestDocumentation;
import org.versly.rest.wsdoc.impl.Utils;

import freemarker.template.TemplateException;

public abstract class AbstractRestAnnotationProcessorTest {
    protected static Map<String,String> output;
    protected static String defaultApiOutput;
    private static File tmpDir;
    protected static final String[] _outputFormats = { "html", "raml" };

    public void setUp() throws IOException, URISyntaxException, ClassNotFoundException, TemplateException {
        File tempFile = File.createTempFile("wsdoc", "tmp");
        tempFile.deleteOnExit();
        tmpDir = new File(tempFile.getParentFile(), "wsdoc-" + System.currentTimeMillis());
        tmpDir.mkdirs();
        tmpDir.deleteOnExit();
    }

    protected void processResource(String fileName, String outputFormat, String scope) {
        processResource(fileName, outputFormat, null, scope, true);
    }

    private void processResource(
            String fileName, String outputFormat, Iterable<Pattern> excludes, 
            String scope, boolean needsTestPackage) {
        try {
            runAnnotationProcessor(tmpDir, fileName, needsTestPackage);
            String outputFile = tmpDir + "/" + fileName.replace(".java", "." + outputFormat);
            List<String> filesWritten = buildOutput(tmpDir, outputFile, outputFormat, excludes, scope);
            readOutput(outputFile, filesWritten);
        } catch (Exception ex) {
            if (ex instanceof RuntimeException)
                throw (RuntimeException) ex;
            else
                throw new RuntimeException(ex);
        }
    }

    private static List<String> buildOutput(
            File buildDir, String outputFile, String outputFormat, Iterable<Pattern> excludes, String scope)
        throws ClassNotFoundException, IOException, TemplateException {

        final InputStream in = new FileInputStream(new File(buildDir, Utils.SERIALIZED_RESOURCE_LOCATION));

        // make the parent dirs in case htmlFile is nested
        new File(outputFile).getParentFile().mkdirs();

        return new RestDocAssembler(outputFile, outputFormat).writeDocumentation(
                new LinkedList<RestDocumentation>() {{ add(RestDocumentation.fromStream(in)); }}, excludes, scope);
    }

    private static void readOutput(String outputFile, List<String> filesWritten) throws IOException {
        output = new LinkedHashMap<String, String>();
        for (String fileWritten : filesWritten) {
            InputStream in = new FileInputStream(fileWritten);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
            String fileContent = "";
            for (String line = null; (line = reader.readLine()) != null; ) {
                fileContent += line + "\n";
            }
            reader.close();
            output.put(fileWritten, fileContent);
            if (fileWritten.equals(outputFile)) {
                defaultApiOutput = fileContent;
            }
        }
    }

    private void runAnnotationProcessor(File buildDir, final String fileName, boolean needsTestPackage)
            throws URISyntaxException, IOException {

        String packagePrefix = "org/versly/rest/wsdoc/" + (needsTestPackage ? getPackageToTest() + "/" : "");
        runAnnotationProcessor(buildDir, packagePrefix, fileName);
    }

    protected static void runAnnotationProcessor(
            File buildDir, final String packagePrefix, final String fileName)
            throws URISyntaxException, IOException {
        AnnotationProcessor processor = new AnnotationProcessor();

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, Collections.singletonList(buildDir));
        JavaFileObject file = new SimpleJavaFileObject(new URI("string:///" + packagePrefix + fileName),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean b) throws IOException {
                InputStream stream = getClass().getClassLoader().getResource(packagePrefix + fileName).openStream();
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                String str = "";
                for (String line = null; (line = reader.readLine()) != null; str += line + "\n")
                    ;
                return str;
            }
        };

        Collection<JavaFileObject> files = Collections.singleton(file);
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, null, null, null, files);
        task.setProcessors(Collections.singleton(processor));
        AssertJUnit.assertTrue(task.call());
    }

    @Test
    public void assertJavaDocComments() {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "all");
            AssertJUnit.assertTrue(
                    "expected 'JavaDoc comment' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("JavaDoc comment"));
        }
    }

    @Test
    public void assertReturnValueComments() {
        processResource("RestDocEndpoint.java", "html", "all");
        AssertJUnit.assertTrue("expected \"exciting return value's date\" in doc string; got: \n" + defaultApiOutput,
                defaultApiOutput.contains("exciting return value's date"));
    }

    @Test
    public void assertPathVariableWithOverriddenName() {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "all");
            AssertJUnit.assertTrue("expected \"dateParam\" in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("dateParam"));
        }
    }

    @Test
    public void assertParams() {
        processResource("RestDocEndpoint.java", "html", "all");
        AssertJUnit.assertTrue("expected param0 and param1 in docs; got: \n" + defaultApiOutput,
                defaultApiOutput.contains(">param0<") && defaultApiOutput.contains(">param1<"));
    }

    @Test
    public void assertOverriddenPaths() {
        processResource("RestDocEndpoint.java", "html", "all");
        AssertJUnit.assertTrue("expected multiple voidreturn sections; got: \n" + defaultApiOutput,
                defaultApiOutput.indexOf("<a id=\"/mount/api/v1/voidreturn") != defaultApiOutput.lastIndexOf("<a id=\"/mount/api/v1/voidreturn"));
    }

    @Test
    public void assertUuidIsNotTraversedInto() {
        processResource("RestDocEndpoint.java", "html", "all");
        AssertJUnit.assertFalse(
                "leastSignificantBits field (member field of UUID class) should not be in results",
                defaultApiOutput.contains("leastSignificantBits"));
        AssertJUnit.assertTrue("expected uuid type somewhere in doc",
                defaultApiOutput.contains("json-primitive-type\">uuid<"));
    }

    @Test
    public void generateExample() {
        for (String format: _outputFormats) {
            processResource("SnowReportController.java", format, "all");
        }
    }

    @Test
    public void nonRecursiveTypeWithMultipleUsesDoesNotHaveRecursionCircles() {
        for (String format: _outputFormats) {
            processResource("NonRecursiveMultiUse.java", format, "all");
            AssertJUnit.assertFalse("should not contain the recursion symbol",
                    defaultApiOutput.contains("&#x21ba;"));
        }
    }

    @Test
    public void assertAllMethods() {
        for (String format : _outputFormats) {
            processResource("AllMethods.java", format, "all");
            AssertJUnit.assertTrue(
                    "expected 'allMethodsGet' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsGet"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsPost' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsPost"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsPut' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsPut"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsDelete' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsDelete"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsHead' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsHead"));
            AssertJUnit.assertTrue(
                    "expected 'allMethodsOptions' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("allMethodsOptions"));
        }
    }

    @Test
    public void assertGenericResponse() {
        for (String format : _outputFormats) {
            processResource("GenericResponse.java", format, "all");
            AssertJUnit.assertTrue("expected 'genericResponseGet' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("genericResponseGet"));
            AssertJUnit.assertTrue("expected 'genericResponsePost' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("genericResponsePost"));
            AssertJUnit.assertTrue("expected 'genericResponsePut' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("genericResponsePut"));
            AssertJUnit.assertTrue("expected 'genericResponseDelete' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("genericResponseDelete"));
            AssertJUnit.assertTrue("expected 'genericResponseHead' in doc string; got: \n" + defaultApiOutput,
                    defaultApiOutput.contains("genericResponseHead"));
            AssertJUnit.assertTrue("expected uuid type somewhere in doc", defaultApiOutput.contains("uuid"));
            AssertJUnit.assertTrue("expected string type somewhere in doc", defaultApiOutput.contains("string"));
            AssertJUnit.assertTrue("expected firstGrandparentField field somewhere in doc",
                    defaultApiOutput.contains("firstGrandparentField"));
        }
    }

    @Test
    public void excludePatterns() {
        for (String format: _outputFormats) {
            processResource("SnowReportController.java", format,
                    Arrays.asList(Pattern.compile("foo"), Pattern.compile(".*snow-report.*")), "all", true);
            AssertJUnit.assertFalse("should not contain the snow-report endpoint",
                    defaultApiOutput.contains("snow-report"));
        }
    }

    @Test
    public void genericTypeResolution() throws IOException, URISyntaxException {
        for (String format: _outputFormats) {
            processResource("RestDocEndpoint.java", format, "all");
        }
    }

    // issue #29
    @Test
    public void assertNoRedundantUriParametersForResource() {
        processResource("RestDocEndpoint.java", "raml", "all");
        Raml raml = new RamlDocumentBuilder().build(defaultApiOutput, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/mount/api/v1/widgets/{id1}/gizmos");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/widgets/{id1}/gizmos not found", resource);
        resource = resource.getResource("/{id2}");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/widgets/{id1}/gizmos/{id2} not found", resource);
    }

    @Test
    public void assertUriParameterNormalization() {
        processResource("UriParameterNormalization.java", "raml", "all");
        Raml raml = new RamlDocumentBuilder().build(defaultApiOutput, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/widgets/{id}");
        AssertJUnit.assertNotNull("Resource /widgets/{id} not found", resource);
        UriParameter id = resource.getUriParameters().get("id");
        AssertJUnit.assertNotNull("Resource /widgets/{id} has no id URI parameter", id);
        // Flakey test depends on order of resource processing
        // AssertJUnit.assertEquals("Resource /widgets/{id} id URI parameter description is wrong",
        // "The widget identifier documented in POST.", id.getDescription().trim());
        resource = resource.getResource("/gadgets");
        AssertJUnit.assertNotNull("Resource /widgets/{id}/gadgets not found", resource);
        id = resource.getUriParameters().get("id");
        AssertJUnit.assertNull("Resource /widgets/{id}/gadgets has it's own id URI parameter when it should not", id);
    }

    @Test
    public void assertUriFirstParameterValidation() {
        processResource("UriFirstParameterValidation.java", "raml", "all");
        Raml raml = new RamlDocumentBuilder().build(defaultApiOutput, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/$base-service.server.api-path/api/v1/group/{id}/participant");
        AssertJUnit.assertNotNull("Resource /$base-service.server.api-path/api/v1/group/{id}/participant", resource);
        UriParameter id = resource.getUriParameters().get("id");
        AssertJUnit.assertNotNull("Resource /$base-service.server.api-path/api/v1/group/{id}/participant has no id URI parameter", id);
    }

    @Test
    public void testEnumsTypesQueryForRaml() {
        processResource("RestDocEndpoint.java", "raml", "all");
        Raml raml = new RamlDocumentBuilder().build(defaultApiOutput, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/mount/api/v1/whirlygigs");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/whirlygigs not found", resource);
        Action action = resource.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("Method GET not found on /mount/api/v1/whirlygigs", action);
        QueryParameter qp = action.getQueryParameters().get("color");
        AssertJUnit.assertNotNull("No color query param found on GET method of /mount/api/v1/whirlygigs", qp);
        List<String> enums = qp.getEnumeration();
        AssertJUnit.assertNotNull("Color query param on GET method of /mount/api/v1/whirlygigs not enum", enums);
        AssertJUnit.assertEquals("Color query param on GET /mount/api/v1/whirlygigs is wrong size", 3, enums.size());
    }

    @Test
    public void testEnumsTypesInPathForRaml() {
        processResource("RestDocEndpoint.java", "raml", "all");
        Raml raml = new RamlDocumentBuilder().build(defaultApiOutput, "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        Resource resource = raml.getResource("/mount/api/v1/colors/{color}");
        AssertJUnit.assertNotNull("Resource /mount/api/v1/colors/{color} not found", resource);
        UriParameter up = resource.getUriParameters().get("color");
        AssertJUnit.assertNotNull("No color path param found on GET method of /mount/api/v1/colors/{color}", up);
        List<String> enums = up.getEnumeration();
        AssertJUnit.assertNotNull("Color path param on GET method of /mount/api/v1/colors/{color} not enum", enums);
        AssertJUnit.assertEquals("Color path param on GET /mount/api/v1/colors/{color} is wrong size", 3, enums.size());
    }
    
    @Test
    public void testPublicationScopes() {
        for (String format : _outputFormats) {
            processResource("PublicationScopes.java", format, "all");
            AssertJUnit.assertTrue(defaultApiOutput.contains("/public1"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/public2"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private2"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private3"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private4"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv4"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/public5/foo"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv5/bar"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/newshakystuff/foo"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/newshakystuff/bar"));

            processResource("PublicationScopes.java", format, "public");
            AssertJUnit.assertTrue(defaultApiOutput.contains("/public2"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private2"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private3"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private4"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv4"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/public5/foo"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv5/bar"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/newshakystuff/foo"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/newshakystuff/bar"));

            processResource("PublicationScopes.java", format, "private");
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public1"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public2"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private2"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private3"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/private4"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv4"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public5/foo"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/pubpriv5/bar"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/newshakystuff/foo"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/newshakystuff/bar"));

            processResource("PublicationScopes.java", format, "experimental");
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public1"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public2"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private2"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private3"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/private4"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/pubpriv4"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/public5/foo"));
            AssertJUnit.assertTrue(!defaultApiOutput.contains("/pubpriv5/bar"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/newshakystuff/foo"));
            AssertJUnit.assertTrue(defaultApiOutput.contains("/newshakystuff/bar"));
        }
    }

    @Test
    public void apiLevelDocs() {
        processResource("ApiLevelDocs.java", "raml", "all");
        AssertJUnit.assertEquals("ApiLevelDocs should have produced exactly 1 results document", 1, output.size());
        Map.Entry<String,String> entry = output.entrySet().iterator().next();
        AssertJUnit.assertTrue("expected file named ApiLevelDocs-UltimateApi.raml",
                entry.getKey().endsWith("ApiLevelDocs-UltimateApi.raml"));
        Raml raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        AssertJUnit.assertEquals("RAML title is incorrect", "The Ultimate REST API", raml.getTitle());
        AssertJUnit.assertEquals("RAML version is incorrect", "v1", raml.getVersion());
        AssertJUnit.assertEquals("RAML baseUri is incorrect", "/ultimate/api/v1", raml.getBaseUri());
        List<DocumentationItem> documentation = raml.getDocumentation();
        AssertJUnit.assertNotNull("RAML has no documentation items", documentation);
        AssertJUnit.assertEquals("RAML has too many documentation items", 1, documentation.size());
        AssertJUnit.assertEquals("RAML documentation item has wrong title", "Overview", documentation.get(0).getTitle());
        AssertJUnit.assertEquals("RAML documentation item has wrong content", "Some documentation of the API itself.",
                documentation.get(0).getContent().trim());
    }

    @Test
    public void multiApiLevelDocs() {
        processResource("MultiApiLevelDocs.java", "raml", "all");
        AssertJUnit.assertEquals("ApuLevelDocs should have produced exactly 2 results document", 2, output.size());
        Iterator<Map.Entry<String, String>> iter = output.entrySet().iterator();

        Map.Entry<String,String> entry = iter.next();
        AssertJUnit.assertTrue("expected file named MultiApiLevelDocs-RestApi1.raml",
                entry.getKey().endsWith("MultiApiLevelDocs-RestApi1.raml"));
        Raml raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML for MultiApiLevelDocs-RestApi1.raml not parseable", raml);
        AssertJUnit.assertEquals("RAML title is incorrect", "The RestApi1 API", raml.getTitle());
        AssertJUnit.assertEquals("RAML version is incorrect", "v1", raml.getVersion());
        AssertJUnit.assertEquals("RAML baseUri is incorrect", "/restapi1/api/v1", raml.getBaseUri());
        List<DocumentationItem> documentation = raml.getDocumentation();
        AssertJUnit.assertNotNull("RAML has no documentation items", documentation);
        AssertJUnit.assertEquals("RAML has too many documentation items", 1, documentation.size());
        AssertJUnit.assertEquals("RAML documentation item has wrong title", "Overview", documentation.get(0).getTitle());
        AssertJUnit.assertEquals("RAML documentation item has wrong content", "This is the header documentation text for RestApi1.",
                documentation.get(0).getContent().trim());
        AssertJUnit.assertEquals("RAML has wrong number of resources", 1, raml.getResources().size());

        entry = iter.next();
        AssertJUnit.assertTrue("expected file named MultiApiLevelDocs-RestApi2.raml",
                entry.getKey().endsWith("MultiApiLevelDocs-RestApi2.raml"));
        raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML for MultiApiLevelDocs-RestApi2.raml not parseable", raml);
        AssertJUnit.assertEquals("RAML title is incorrect", "The RestApi2 API", raml.getTitle());
        AssertJUnit.assertEquals("RAML version is incorrect", "v1", raml.getVersion());
        AssertJUnit.assertEquals("RAML baseUri is incorrect", "/restapi2/api/v1", raml.getBaseUri());
        documentation = raml.getDocumentation();
        AssertJUnit.assertNotNull("RAML has no documentation items", documentation);
        AssertJUnit.assertEquals("RAML has too many documentation items", 1, documentation.size());
        AssertJUnit.assertEquals("RAML documentation item has wrong title", "Overview", documentation.get(0).getTitle());
        AssertJUnit.assertTrue("RAML documentation item has wrong content",
                documentation.get(0).getContent().trim().startsWith("This is the header documentation text for RestApi2."));
        AssertJUnit.assertEquals("RAML has wrong number of resources", 1, raml.getResources().size());
        AssertJUnit.assertEquals("RAML has wrong number of resources", 2, raml.getResources().values().iterator().next().getResources().size());
    }

    @Test
    public void stabilitySettings() {
        processResource("TraitsAnnotations.java", "raml", "all");
        AssertJUnit.assertEquals("Exactly one output file expected", 1, output.size());
        Iterator<Map.Entry<String, String>> iter = output.entrySet().iterator();
        Map.Entry<String, String> entry = iter.next();
        AssertJUnit.assertTrue("expected file named TraitsAnnotations.raml",
                entry.getKey().endsWith("TraitsAnnotations.raml"));
        
        Raml raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML for Stability.raml not parseable", raml);

        List<Map<String,Template>> traits = raml.getTraits();
        AssertJUnit.assertNotNull("RAML expected to define traits", traits);
        AssertJUnit.assertEquals("RAML expected to define 2 traits", 2, traits.size());
        AssertJUnit.assertTrue("RAML expected to contain experimental trait", traits.get(0).containsKey("experimental"));
        AssertJUnit.assertTrue("RAML expected to contain deprecated trait", traits.get(1).containsKey("deprecated"));

        Resource res = raml.getResource("/stable1");
        AssertJUnit.assertNotNull("resource /stable1 not found", res);
        Action act = res.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("method GET /stable1 not found", act);
        List<String> is = act.getIs();
        AssertJUnit.assertNotNull("resource /stable1 has no \'is\'", is);
        AssertJUnit.assertEquals("resource /stable1 should have empty \'is\'", 0, is.size());

        res = raml.getResource("/deprecated2");
        AssertJUnit.assertNotNull("resource /deprecated2 not found", res);
        act = res.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("method GET /deprecated2 not found", act);
        is = act.getIs();
        AssertJUnit.assertNotNull("resource /deprecated2 has no \'is\'", is);
        AssertJUnit.assertEquals("resource /deprecated2 should have one \'is\'", 1, is.size());
        AssertJUnit.assertEquals("resource /deprecated2 should be deprecated", "deprecated", is.iterator().next());

        res = raml.getResource("/stable3");
        AssertJUnit.assertNotNull("resource /stable3 not found", res);
        act = res.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("method GET /stable3 not found", act);
        is = act.getIs();
        AssertJUnit.assertNotNull("resource /stable3 has no \'is\'", is);
        AssertJUnit.assertEquals("resource /stable3 should have empty \'is\'", 0, is.size());

        res = raml.getResource("/deprecated3");
        AssertJUnit.assertNotNull("resource /deprecated3 not found", res);
        act = res.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("method GET /deprecated3 not found", act);
        is = act.getIs();
        AssertJUnit.assertNotNull("resource /deprecated3 has no \'is\'", is);
        AssertJUnit.assertEquals("resource /deprecated3 should have one \'is\'", 1, is.size());
        AssertJUnit.assertEquals("resource /deprecated3 should be deprecated", "deprecated", is.iterator().next());

        res = raml.getResource("/experimentaldeprecated3");
        AssertJUnit.assertNotNull("resource /experimentaldeprecated3 not found", res);
        act = res.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("method GET /experimentaldeprecated3 not found", act);
        is = act.getIs();
        AssertJUnit.assertNotNull("resource /experimentaldeprecated3 has no \'is\'", is);
        AssertJUnit.assertEquals("resource /experimentaldeprecated3 should have one \'is\'", 2, is.size());
        Iterator<String> iter2 = is.iterator();
        AssertJUnit.assertEquals("resource /experimentaldeprecated3 should include experimental", "experimental", iter2.next());
        AssertJUnit.assertEquals("resource /experimentaldeprecated3 should include deprecated", "deprecated", iter2.next());
    }

    @Test
    public void authScopeDocs() {
        processResource("AuthorizationScopes.java", "raml", "all");
        AssertJUnit.assertEquals("AuthorizationScopes should have produced exactly 1 results document", 1, output.size());
        Map.Entry<String,String> entry = output.entrySet().iterator().next();
        AssertJUnit.assertTrue("expected file named AuthorizationScopes.raml",
                entry.getKey().endsWith("AuthorizationScopes.raml"));
        Raml raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        List<DocumentationItem> documentation = raml.getDocumentation();
        Resource resource = raml.getResource("/default/api/v1/default");
        AssertJUnit.assertNotNull("RAML has no default controller", resource);
        Action action = resource.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("RAML default controller has no get action", action);
        List<SecurityReference> secRef = action.getSecuredBy();
        AssertJUnit.assertNotNull("RAML has no default security reference list", secRef);
        AssertJUnit.assertTrue("RAML default controller has a security reference", secRef.size() == 0);

        resource = raml.getResource("/twoscopes/api/v1/twoscope");
        AssertJUnit.assertNotNull("RAML has no twoscope controller", resource);
        action = resource.getAction(ActionType.POST);
        AssertJUnit.assertNotNull("RAML twoscope controller has no get action", action);
        secRef = action.getSecuredBy();
        AssertJUnit.assertNotNull("RAML has no twoscope security reference list", secRef);
        AssertJUnit.assertTrue("RAML twoscope controller get does not have a security reference", secRef.size() == 1);
        AssertJUnit.assertEquals("RAML twoscope controller get does not have the expected security reference", "oauth_2_0", secRef.get(0).getName());
        Map<String, List<String>> parameters = secRef.get(0).getParameters();
        AssertJUnit.assertNotNull("RAML twoscope secref does not have parameters", parameters);
        AssertJUnit.assertEquals("RAML twoscope secref parameters map does not contain one entry", 1, parameters.size());
        List<String> scopes = parameters.get("scopes");
        AssertJUnit.assertNotNull("RAML twoscope secref parameters map does not contain scopes", scopes);
        AssertJUnit.assertTrue("RAML twoscope secref parameters does not include two_scope_service:write scope", scopes.contains("two_scope_service:write"));
        AssertJUnit.assertTrue("RAML twoscope secref parameter includes two_scope_service:read scope", !scopes.contains("two_scope_service:read"));
        AssertJUnit.assertTrue("RAML twoscope secref parameters includes two_scope_service:admin scope", !scopes.contains("two_scope_service:admin"));

        action = resource.getAction(ActionType.GET);
        AssertJUnit.assertNotNull("RAML twoscope controller has no post action", action);
        secRef = action.getSecuredBy();
        AssertJUnit.assertNotNull("RAML has no twoscope security reference list", secRef);
        AssertJUnit.assertTrue("RAML twoscope controller get does not have a security reference", secRef.size() == 1);
        AssertJUnit.assertEquals("RAML twoscope controller get does not have the expected security reference", "oauth_2_0", secRef.get(0).getName());
        parameters = secRef.get(0).getParameters();
        AssertJUnit.assertNotNull("RAML twoscope secref does not have parameters", parameters);
        AssertJUnit.assertEquals("RAML twoscope secref parameters map does not contain one entry", 1, parameters.size());
        scopes = parameters.get("scopes");
        AssertJUnit.assertNotNull("RAML twoscope secref parameters map does not contain scopes", scopes);
        AssertJUnit.assertTrue("RAML twoscope secref parameters does not include two_scope_service:read scope", scopes.contains("two_scope_service:read"));
        AssertJUnit.assertTrue("RAML twoscope secref parameters includes two_scope_service:write scope", !scopes.contains("two_scope_service:write"));
        AssertJUnit.assertTrue("RAML twoscope secref parameters does not include two_scope_service:admin scope", scopes.contains("two_scope_service:admin"));
    }

    @Test
    public void docTemplate() {
        String mountPoint = "/foo";
        Utils.addTemplateValue(DocumentationRestApi.ID_TEMPLATE, "fooID");
        Utils.addTemplateValue(DocumentationRestApi.MOUNT_TEMPLATE, mountPoint);
        processResource("ApiLevelTemplateDocs.java", "raml", "all");
        Map.Entry<String, String> entry = output.entrySet().iterator().next();
                entry.getKey();
        AssertJUnit.assertTrue("expected file named AuthorizationScopes.raml",
                entry.getKey().endsWith("ApiLevelTemplateDocs-fooID.raml"));
        Raml raml = new RamlDocumentBuilder().build(entry.getValue(), "http://example.com");
        AssertJUnit.assertNotNull("RAML not parseable", raml);
        List<DocumentationItem> documentation = raml.getDocumentation();
        AssertJUnit.assertEquals("RAML baseUri is incorrect", mountPoint, raml.getBaseUri());
        Resource resource = raml.getResource(mountPoint);
        AssertJUnit.assertNotNull("Cannot find resource related to:" + mountPoint, resource);
    }

    protected abstract String getPackageToTest();
}
