package com.quorum.tessera.sync;

import com.quorum.tessera.api.model.ResendRequest;
import com.quorum.tessera.client.P2pClient;
import com.quorum.tessera.encryption.Enclave;
import com.quorum.tessera.encryption.PublicKey;

import java.net.URI;
import java.util.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Set;

import java.util.Collections;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

public class TransactionRequesterTest {

    private static final PublicKey KEY_ONE = PublicKey.from(new byte[]{1});

    private static final PublicKey KEY_TWO = PublicKey.from(new byte[]{2});

    private static final URI TARGET = URI.create("http://example.com");

    private Enclave enclave;

    private P2pClient p2pClient;

    private TransactionRequester transactionRequester;

    @Before
    public void init() {

        this.enclave = mock(Enclave.class);
        this.p2pClient = mock(P2pClient.class);

        doReturn(true).when(p2pClient).makeResendRequest(any(URI.class), any(ResendRequest.class));

        this.transactionRequester = new TransactionRequesterImpl(enclave, p2pClient);
    }

    @After
    public void after() {
        verifyNoMoreInteractions(enclave, p2pClient);
    }

    @Test
    public void noPublicKeysMakesNoCalls() {
        when(enclave.getPublicKeys()).thenReturn(Collections.emptySet());

        this.transactionRequester.requestAllTransactionsFromNode(TARGET);

        verifyZeroInteractions(p2pClient);
        verify(enclave).getPublicKeys();
    }

    @Test
    public void multipleKeysMakesCorrectCalls() {
        
        final Set<PublicKey> allKeys = Stream.of(KEY_ONE, KEY_TWO).collect(Collectors.toSet());

        when(enclave.getPublicKeys()).thenReturn(allKeys);
        
        this.transactionRequester.requestAllTransactionsFromNode(TARGET);

        final ArgumentCaptor<ResendRequest> captor = ArgumentCaptor.forClass(ResendRequest.class);
        verify(p2pClient, times(2)).makeResendRequest(eq(TARGET), captor.capture());
        verify(enclave).getPublicKeys();

        String encodedKeyOne = Base64.getEncoder().encodeToString(KEY_ONE.getKeyBytes());
        String encodedKeyTwo = Base64.getEncoder().encodeToString(KEY_TWO.getKeyBytes());
        
        assertThat(captor.getAllValues())
            .hasSize(2)
            .extracting("publicKey")
            .containsExactlyInAnyOrder(encodedKeyOne, encodedKeyTwo);
    }

    @Test
    public void failedCallRetries() {
        when(enclave.getPublicKeys()).thenReturn(Collections.singleton(KEY_ONE));
        
        when(p2pClient.makeResendRequest(any(URI.class), any(ResendRequest.class))).thenReturn(false);

        this.transactionRequester.requestAllTransactionsFromNode(TARGET);

        verify(p2pClient, times(5)).makeResendRequest(eq(TARGET), any(ResendRequest.class));
        verify(enclave).getPublicKeys();

    }

    @Test
    public void calltoPostDelegateThrowsException() {

        when(enclave.getPublicKeys()).thenReturn(Collections.singleton(KEY_ONE));
        when(p2pClient.makeResendRequest(any(URI.class), any(ResendRequest.class))).thenThrow(RuntimeException.class);
        
        this.transactionRequester.requestAllTransactionsFromNode(TARGET);

        verify(p2pClient, times(5)).makeResendRequest(eq(TARGET), any(ResendRequest.class));
        verify(enclave).getPublicKeys();

    }
}
