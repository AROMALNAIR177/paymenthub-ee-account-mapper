package org.mifos.identityaccountmapper.service;

import static org.mifos.identityaccountmapper.util.PaymentModalityEnum.getKeyByValue;
import static org.mifos.identityaccountmapper.util.PaymentModalityEnum.getValueByKey;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.mifos.identityaccountmapper.config.LookupProperties;
import org.mifos.identityaccountmapper.data.BatchAccountLookupResponseDTO;
import org.mifos.identityaccountmapper.data.BeneficiaryDTO;
import org.mifos.identityaccountmapper.domain.IdentityDetails;
import org.mifos.identityaccountmapper.domain.PaymentModalityDetails;
import org.mifos.identityaccountmapper.exception.PayeeIdentityException;
import org.mifos.identityaccountmapper.repository.MasterRepository;
import org.mifos.identityaccountmapper.repository.PaymentModalityRepository;
import org.mifos.pheeidaccountvalidatorimpl.service.AccountValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AccountLookupService {

    private final MasterRepository masterRepository;
    private final PaymentModalityRepository paymentModalityRepository;
    private final SendCallbackService sendCallbackService;
    private final ObjectMapper objectMapper;
    private final AccountLookupReadService accountLookupReadService;
    private final LookupProperties lookupProperties;
    @Autowired
    private ApplicationContext applicationContext;

    private static final Logger logger = LoggerFactory.getLogger(AccountLookupService.class);
    private final Set<String> paymentModalityCodes = new HashSet<>(Set.of("00", "01", "02"));

    @Autowired
    public AccountLookupService(MasterRepository masterRepository, PaymentModalityRepository paymentModalityRepository,
            SendCallbackService sendCallbackService, ObjectMapper objectMapper, AccountLookupReadService accountLookupReadService,
            LookupProperties lookupProperties) {
        this.masterRepository = masterRepository;
        this.paymentModalityRepository = paymentModalityRepository;
        this.sendCallbackService = sendCallbackService;
        this.objectMapper = objectMapper;
        this.accountLookupReadService = accountLookupReadService;
        this.lookupProperties = lookupProperties;
    }

    @Async("asyncExecutor")
    public void batchAccountLookup(String callbackURL, String requestId, List<BeneficiaryDTO> beneficiariesList,
            String registeringInstitutionId) {
        List<BeneficiaryDTO> accountLookupList = new ArrayList<>();
        beneficiariesList.stream().forEach(beneficiary -> {
            if (masterRepository.existsByPayeeIdentityAndRegisteringInstitutionId(beneficiary.getPayeeIdentity(),
                    registeringInstitutionId)) {
                IdentityDetails identityDetails = masterRepository
                        .findByPayeeIdentityAndRegisteringInstitutionId(beneficiary.getPayeeIdentity(), registeringInstitutionId)
                        .orElseThrow(() -> PayeeIdentityException.payeeIdentityNotFound(beneficiary.getPayeeIdentity()));
                PaymentModalityDetails paymentModalityDetails = paymentModalityRepository.findByMasterId(identityDetails.getMasterId())
                        .get(0);

                if (lookupProperties.accountValidationEnabled()) {
                    // handled as in accountlookupHelper (the single lookup): a missing validator bean, a validator
                    // that throws, or one that answers null all mean "not validated" for this beneficiary only
                    AccountValidationService accountValidationService;
                    try {
                        accountValidationService = (AccountValidationService) this.applicationContext
                                .getBean(lookupProperties.accountValidatorConnector());
                    } catch (NoSuchBeanDefinitionException ex) {
                        logger.error("AccountValidationService bean not found: {}", lookupProperties.accountValidatorConnector(), ex);
                        return;
                    }
                    Boolean accountValidate;
                    try {
                        accountValidate = accountValidationService.validateAccount(paymentModalityDetails.getDestinationAccount(),
                                paymentModalityDetails.getInstitutionCode(), fetchPaymentModality(paymentModalityDetails.getModality()),
                                beneficiary.getPayeeIdentity(), callbackURL);
                    } catch (Exception ex) {
                        logger.error("Error during account validation for {}", beneficiary.getPayeeIdentity(), ex);
                        return;
                    }
                    if (!Boolean.TRUE.equals(accountValidate)) {
                        return;
                    }
                }
                accountLookupList.add(new BeneficiaryDTO(beneficiary.getPayeeIdentity(), paymentModalityDetails.getModality(),
                        paymentModalityDetails.getDestinationAccount(), paymentModalityDetails.getInstitutionCode()));

            }
        });
        BatchAccountLookupResponseDTO batchAccountLookupResponse = new BatchAccountLookupResponseDTO(requestId, registeringInstitutionId,
                accountLookupList);
        try {
            sendCallbackService.sendCallback(objectMapper.writeValueAsString(batchAccountLookupResponse), callbackURL);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Async("asyncExecutor")
    public void accountLookup(String callbackURL, String payeeIdentity, String paymentModality, String requestId,
            String registeringInstitutionId) {
        IdentityDetails identityDetails = masterRepository
                .findByPayeeIdentityAndRegisteringInstitutionId(payeeIdentity, registeringInstitutionId)
                .orElseThrow(() -> PayeeIdentityException.payeeIdentityNotFound(payeeIdentity));
        if (!identityDetails.getRegisteringInstitutionId().matches(registeringInstitutionId)) {
            sendCallbackService.sendCallback("Registering Institution Id is not mapped to the Payee Identity provided in the request.",
                    callbackURL);
            return;
        }
        logger.info("Before helper function");
        Boolean accountValidate = accountlookupHelper(callbackURL, payeeIdentity, paymentModality, identityDetails);
        sendAccountLookupCallback(callbackURL, accountValidate, payeeIdentity, requestId, registeringInstitutionId);
    }

    public boolean accountlookupHelper(String callbackURL, String payeeIdentity, String paymentModality, IdentityDetails identityDetails) {

        PaymentModalityDetails paymentModalityDetails = paymentModalityRepository.findByMasterId(identityDetails.getMasterId()).get(0);

        if (!paymentModalityCodes.contains(paymentModality)) {
            paymentModality = getValueByKey(paymentModality);
        }

        AccountValidationService accountValidationService;
        try {
            accountValidationService = (AccountValidationService) this.applicationContext
                    .getBean(lookupProperties.accountValidatorConnector());
        } catch (NoSuchBeanDefinitionException ex) {
            logger.error("AccountValidationService bean not found: {}", lookupProperties.accountValidatorConnector(), ex);
            return false;
        }

        Boolean accountValidate;
        try {
            accountValidate = accountValidationService.validateAccount(paymentModalityDetails.getDestinationAccount(),
                    paymentModalityDetails.getInstitutionCode(), fetchPaymentModality(paymentModality), payeeIdentity, callbackURL);
        } catch (Exception ex) {
            logger.error("Error during account validation", ex);
            return false;
        }

        return true;
    }

    public void sendAccountLookupCallback(String callbackURL, Boolean accountValidate, String payeeIdentity, String requestId,
            String registeringInstitutionId) {
        try {
            sendCallbackService.sendCallback(objectMapper.writeValueAsString(
                    accountLookupReadService.lookup(payeeIdentity, callbackURL, requestId, registeringInstitutionId, accountValidate)),
                    callbackURL);
        } catch (JsonProcessingException | RuntimeException e) {
            logger.error(e.getMessage());
        }
    }

    public String fetchPaymentModality(String paymentModality) {
        return getKeyByValue(paymentModality);
    }
}
