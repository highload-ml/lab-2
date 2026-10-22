package ru.itmo.highload_ml.tracking.client;
import java.util.UUID;
import java.util.Map;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(MockitoExtension.class)
class FeignProjectAccessTest {
    @Mock ProjectClient client;
    UUID experiment = UUID.randomUUID(), project = UUID.randomUUID(), author = UUID.randomUUID();
    @Test void checksMembershipOnlyForActiveExperiment() {
        when(client.experiment(experiment)).thenReturn(new ProjectClient.ExperimentDetails(experiment, project, true));
        when(client.membership(project, author)).thenReturn(new ProjectClient.MembershipDetails(project, author, "EDITOR"));
        StepVerifier.create(new FeignProjectAccess(client).requireAuthor(experiment, author)).verifyComplete();
        verify(client).membership(project, author);
    }
    @Test void rejectsInactiveExperimentWithoutMembershipCall() {
        when(client.experiment(experiment)).thenReturn(new ProjectClient.ExperimentDetails(experiment, project, false));
        StepVerifier.create(new FeignProjectAccess(client).requireAuthor(experiment, author))
                .expectErrorSatisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404)).verify();
        verifyNoMoreInteractions(client);
    }
    @ParameterizedTest @CsvSource({"404,404", "422,422", "500,503", "503,503"})
    void mapsRemoteFailuresWithoutLeakingBody(int upstreamStatus, int expectedStatus) {
        var request = Request.create(Request.HttpMethod.GET, "http://project/internal", Map.of(), null, java.nio.charset.StandardCharsets.UTF_8, null);
        var error = FeignException.errorStatus("experiment", Response.builder().request(request).status(upstreamStatus).reason("remote")
                .body("secret remote details", java.nio.charset.StandardCharsets.UTF_8).build());
        when(client.experiment(experiment)).thenThrow(error);
        StepVerifier.create(new FeignProjectAccess(client).requireActiveExperiment(experiment)).expectErrorSatisfies(failure -> {
            assertThat(((ResponseStatusException) failure).getStatusCode().value()).isEqualTo(expectedStatus);
            assertThat(failure.getMessage()).doesNotContain("secret");
        }).verify();
    }
}
