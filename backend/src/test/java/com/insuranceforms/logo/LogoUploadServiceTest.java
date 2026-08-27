package com.insuranceforms.logo;

import com.insuranceforms.agent.Agent;
import com.insuranceforms.agent.AgentRepository;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Plain unit tests -- no Spring context, no database, no real image processing.
// AgentRepository and LogoImageProcessor are Mockito mocks; LogoStorage is a
// hand-written recording fake (RecordingLogoStorage below) rather than a mock, so the
// exact call order (store / save / delete) can be asserted against one shared list
// without relying on Mockito's InOrder API.
class LogoUploadServiceTest {

    private final AgentRepository agentRepository = mock(AgentRepository.class);
    private final LogoImageProcessor processor = mock(LogoImageProcessor.class);
    private final List<String> calls = new ArrayList<>();
    private final RecordingLogoStorage logoStorage = new RecordingLogoStorage();
    private final LogoUploadService service = new LogoUploadService(agentRepository, logoStorage, processor);

    private Agent stubAgent(UUID agentId, UUID existingLogoId) {
        Agent agent = new Agent("Ruth Cohen", "ruth@example.com", "hash", "123456782",
                "Cohen Insurance", "0501234567", "AG-1");
        agent.setLogoId(existingLogoId);
        when(agentRepository.findById(agentId)).thenReturn(Optional.of(agent));
        when(agentRepository.save(any())).thenAnswer(invocation -> {
            calls.add("save");
            return invocation.getArgument(0);
        });
        when(processor.process(any())).thenReturn(new byte[]{1, 2, 3});
        return agent;
    }

    @Test
    void storesTheNewLogoPointsTheAgentAtItThenDeletesTheOldOne() {
        UUID agentId = UUID.randomUUID();
        UUID oldLogoId = UUID.randomUUID();
        Agent agent = stubAgent(agentId, oldLogoId);

        service.replaceLogo(agentId, new byte[]{9, 9, 9});

        assertThat(calls).containsExactly("store", "save", "delete");
        assertThat(agent.getLogoId()).isEqualTo(logoStorage.lastStoredId);
        assertThat(logoStorage.lastDeletedId).isEqualTo(oldLogoId);
    }

    @Test
    void doesNotAttemptToDeleteWhenThereIsNoExistingLogo() {
        UUID agentId = UUID.randomUUID();
        stubAgent(agentId, null);

        service.replaceLogo(agentId, new byte[]{9, 9, 9});

        assertThat(calls).containsExactly("store", "save");
    }

    // The invariant this protects: a delete failure must never leave agent.logoId
    // pointing at a missing file. That's only true if the point-to-new-logo save
    // already happened before delete is attempted -- which the call-order assertion
    // below proves directly, rather than inferring it from the absence of an exception.
    @Test
    void aDeleteFailureIsLoggedNotPropagatedAndTheAgentAlreadyPointsAtTheNewLogo() {
        UUID agentId = UUID.randomUUID();
        UUID oldLogoId = UUID.randomUUID();
        Agent agent = stubAgent(agentId, oldLogoId);
        logoStorage.idToFailDeleteFor = oldLogoId;

        assertThatCode(() -> service.replaceLogo(agentId, new byte[]{9, 9, 9})).doesNotThrowAnyException();

        assertThat(calls).containsExactly("store", "save", "delete");
        assertThat(agent.getLogoId()).isEqualTo(logoStorage.lastStoredId);
    }

    private class RecordingLogoStorage implements LogoStorage {
        UUID lastStoredId;
        UUID lastDeletedId;
        UUID idToFailDeleteFor;

        @Override
        public LogoRef store(byte[] bytes) {
            lastStoredId = UUID.randomUUID();
            calls.add("store");
            return new LogoRef(lastStoredId, "/logos/" + lastStoredId);
        }

        @Override
        public InputStream resolve(UUID id) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public void delete(UUID id) {
            lastDeletedId = id;
            calls.add("delete");
            if (id.equals(idToFailDeleteFor)) {
                throw new RuntimeException("disk unavailable");
            }
        }
    }
}
