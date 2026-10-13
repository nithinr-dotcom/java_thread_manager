package com.fleet.storefront.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;
import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Clean-architecture boundaries and the code-level invariants from CONTRIBUTING.md. A failure message here
 * names the rule that was broken — fix the code, never loosen the rule without an ADR.
 *
 * <pre>
 * config ──▶ adapter.in / adapter.out ──▶ application.port.in / port.out ──▶ domain
 *              (web)      (jdbc, outbox,     application.service implements port.in,
 *                          kitchen, hq)      uses port.out
 * </pre>
 */
@AnalyzeClasses(packages = "com.fleet.storefront", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String DOMAIN = "..storefront.domain..";
    private static final String APPLICATION = "..storefront.application..";
    private static final String PORT_IN = "..storefront.application.port.in..";
    private static final String PORT_OUT = "..storefront.application.port.out..";
    private static final String SERVICE = "..storefront.application.service..";
    private static final String ADAPTER = "..storefront.adapter..";
    private static final String ADAPTER_IN = "..storefront.adapter.in..";
    private static final String ADAPTER_OUT = "..storefront.adapter.out..";
    private static final String CONFIG = "..storefront.config..";

    // --- Layers --------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule layersPointInwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy(DOMAIN)
            .layer("Application").definedBy(APPLICATION)
            .layer("Adapters").definedBy(ADAPTER)
            .layer("Config").definedBy(CONFIG)
            .whereLayer("Config").mayNotBeAccessedByAnyLayer()
            .whereLayer("Adapters").mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application").mayOnlyBeAccessedByLayers("Adapters", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Adapters", "Config");

    @ArchTest
    static final ArchRule domainIsPureJava = classes()
            .that().resideInAPackage(DOMAIN)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN)
            .because("the domain is framework-free (CONTRIBUTING.md: domain imports only java.*)");

    @ArchTest
    static final ArchRule applicationIsPureJava = classes()
            .that().resideInAPackage(APPLICATION)
            .should().onlyDependOnClassesThat().resideInAnyPackage("java..", DOMAIN, APPLICATION)
            .because("use cases must be testable with in-memory fakes, without Spring or a database");

    // --- Ports -----------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule outputPortsAreInterfaces = classes()
            .that().resideInAPackage(PORT_OUT).and().areTopLevelClasses()
            .should().beInterfaces()
            .because("output ports are contracts implemented by adapters");

    @ArchTest
    static final ArchRule inputPortsAreNotImplementations = noClasses()
            .that().resideInAPackage(PORT_IN)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, PORT_OUT)
            .because("input ports describe what a use case offers, not how it is implemented");

    @ArchTest
    static final ArchRule adaptersUseCasesOnlyThroughInputPorts = noClasses()
            .that().resideInAPackage(ADAPTER)
            .should().dependOnClassesThat().resideInAPackage(SERVICE)
            .because("inbound adapters call input-port interfaces; only config knows the concrete services");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotCallOutboundAdapters = noClasses()
            .that().resideInAPackage(ADAPTER_IN)
            .should().dependOnClassesThat().resideInAPackage(ADAPTER_OUT)
            .because("a controller reaches the database only through a use case");

    @ArchTest
    static final ArchRule inboundAdaptersDoNotUseOutputPorts = noClasses()
            .that().resideInAPackage(ADAPTER_IN)
            .should().dependOnClassesThat().resideInAPackage(PORT_OUT)
            .because("a controller reaches the database only through a use case");

    @ArchTest
    static final ArchRule outboundAdaptersDoNotCallInboundAdapters = noClasses()
            .that().resideInAPackage(ADAPTER_OUT)
            .should().dependOnClassesThat().resideInAPackage(ADAPTER_IN);

    // --- Invariants --------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule noFloatingPointAnywhere = classes()
            .should(notUseFloatingPointTypes())
            .because("money is BigDecimal + Currency via Money (CONTRIBUTING.md invariant 6)");

    @ArchTest
    static final ArchRule idsAreUuidV7 = noClasses()
            .should().callMethod(UUID.class, "randomUUID")
            .because("entity ids are UUIDv7 from UuidV7 (CONTRIBUTING.md invariant 7)");

    @ArchTest
    static final ArchRule downstreamHttpOnlyFromTheHqAdapter = noClasses()
            .that().resideOutsideOfPackage("..storefront.adapter.out.hq..")
            .should().dependOnClassesThat(httpClients())
            .because("kitchen/HQ are reached only via the transactional outbox relay (CONTRIBUTING.md invariant 5)");

    @ArchTest
    static final ArchRule useCasesNeverTouchOutboxDelivery = noClasses()
            .that().resideInAPackage(APPLICATION)
            .should().dependOnClassesThat().resideInAnyPackage("..adapter.out.outbox..", "..adapter.out.kitchen..",
                    "..adapter.out.hq..")
            .because("checkout only appends to the Outbox port inside its transaction (CONTRIBUTING.md invariant 5)");

    @ArchTest
    static final ArchRule jdbcResultsAreNeverLazyStreams = noClasses()
            .should().callMethod(JdbcClient.MappedQuerySpec.class, "stream")
            .because("a lazy JdbcClient stream keeps its ResultSet and pooled connection open until closed; outside a "
                    + "transaction that leaks a connection per call and exhausts the pool — use .list()");

    private static DescribedPredicate<JavaClass> httpClients() {
        Set<String> names = Set.of(
                "org.springframework.web.client.RestClient",
                "org.springframework.web.client.RestTemplate",
                "org.springframework.web.reactive.function.client.WebClient",
                "java.net.http.HttpClient",
                "java.net.HttpURLConnection");
        return DescribedPredicate.describe("an HTTP client", c -> names.contains(c.getName()));
    }

    private static ArchCondition<JavaClass> notUseFloatingPointTypes() {
        Set<String> forbidden = Set.of("double", "float", "java.lang.Double", "java.lang.Float");
        return new ArchCondition<>("not declare double/float fields, parameters or return types") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                for (JavaField field : clazz.getFields()) {
                    if (forbidden.contains(field.getRawType().getName())) {
                        events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is floating point"));
                    }
                }
                for (JavaCodeUnit unit : clazz.getCodeUnits()) {
                    if (forbidden.contains(unit.getRawReturnType().getName())) {
                        events.add(SimpleConditionEvent.violated(unit, unit.getFullName() + " returns floating point"));
                    }
                    unit.getRawParameterTypes().stream()
                            .filter(p -> forbidden.contains(p.getName()))
                            .forEach(p -> events.add(SimpleConditionEvent.violated(unit,
                                    unit.getFullName() + " takes a floating point parameter")));
                }
            }
        };
    }
}
