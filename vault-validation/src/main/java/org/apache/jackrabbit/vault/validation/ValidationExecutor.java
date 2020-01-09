/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.vault.validation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import org.apache.commons.io.FilenameUtils;
import org.apache.jackrabbit.vault.util.Constants;
import org.apache.jackrabbit.vault.util.PlatformNameFormat;
import org.apache.jackrabbit.vault.validation.impl.util.EnhancedBufferedInputStream;
import org.apache.jackrabbit.vault.validation.impl.util.ResettableInputStream;
import org.apache.jackrabbit.vault.validation.spi.DocumentViewXmlValidator;
import org.apache.jackrabbit.vault.validation.spi.FilterValidator;
import org.apache.jackrabbit.vault.validation.spi.GenericJcrDataValidator;
import org.apache.jackrabbit.vault.validation.spi.GenericMetaInfDataValidator;
import org.apache.jackrabbit.vault.validation.spi.JcrPathValidator;
import org.apache.jackrabbit.vault.validation.spi.MetaInfPathValidator;
import org.apache.jackrabbit.vault.validation.spi.NodePathValidator;
import org.apache.jackrabbit.vault.validation.spi.PropertiesValidator;
import org.apache.jackrabbit.vault.validation.spi.ValidationContext;
import org.apache.jackrabbit.vault.validation.spi.ValidationMessage;
import org.apache.jackrabbit.vault.validation.spi.ValidationMessageSeverity;
import org.apache.jackrabbit.vault.validation.spi.Validator;
import org.apache.jackrabbit.vault.validation.spi.impl.AdvancedFilterValidator;
import org.apache.jackrabbit.vault.validation.spi.impl.AdvancedPropertiesValidator;
import org.apache.jackrabbit.vault.validation.spi.impl.DocumentViewParserValidator;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 
 * Provides methods to call all registered validators. This instance is bound to the {@link ValidationContext} being given in the 
 * {@link ValidationExecutorFactory#createValidationExecutor(org.apache.jackrabbit.vault.validation.spi.ValidationContext, boolean, boolean, Map)}.
 * This class is thread-safe (i.e. methods can be used from different threads on the same instance). 
 * @see ValidationExecutorFactory
 */
public class ValidationExecutor {

    private final Map<String, DocumentViewXmlValidator> documentViewXmlValidators;
    private final Map<String, NodePathValidator> nodePathValidators;
    private final Map<String, GenericJcrDataValidator> genericJcrDataValidators;
    private final Map<String, GenericMetaInfDataValidator> genericMetaInfDataValidators;
    private final Map<String, MetaInfPathValidator> metaInfPathValidators;
    private final Map<String, JcrPathValidator> jcrPathValidators;
    private final Map<String, FilterValidator> filterValidators;
    private final Map<String, PropertiesValidator> propertiesValidators;
    private final Map<String, Validator> validatorsById;

    /**
     * the default logger
     */
    private static final Logger log = LoggerFactory.getLogger(ValidationExecutor.class);

    /**
     * Creates a new instance with the given validators.
     * 
     * @param validatorsById a map of validator ids and actual validators
     */
    public ValidationExecutor(@NotNull Map<String, Validator> validatorsById) {
        this.validatorsById = validatorsById;
        this.documentViewXmlValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, DocumentViewXmlValidator.class);
        this.nodePathValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, NodePathValidator.class);
        this.genericJcrDataValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, GenericJcrDataValidator.class);
        this.genericMetaInfDataValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, GenericMetaInfDataValidator.class);
        this.metaInfPathValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, MetaInfPathValidator.class);
        this.jcrPathValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, JcrPathValidator.class);
        this.filterValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, FilterValidator.class);
        this.propertiesValidators = ValidationExecutor.filterValidatorsByClass(validatorsById, PropertiesValidator.class);
        
        // nested validators (i.e. ones called from specific low-level validators) need to be linked
        for (Validator validator : validatorsById.values()) {
            if (validator instanceof AdvancedFilterValidator) {
                AdvancedFilterValidator.class.cast(validator).setFilterValidators(filterValidators);
            }
            if (validator instanceof AdvancedPropertiesValidator) {
                AdvancedPropertiesValidator.class.cast(validator).setPropertiesValidators(propertiesValidators);
            }
            if (validator instanceof DocumentViewParserValidator) {
                DocumentViewParserValidator.class.cast(validator).setDocumentViewXmlValidators(documentViewXmlValidators);
            }
        }
    }

    /**
     * Returns all bound validators by id.
     * @return a map with all validators (key=validator id, value=actual validator)
     */
    public @NotNull Map<String, Validator> getAllValidatorsById() {
        return validatorsById;
    }

    /**
     * Returns all unused validators by id. Unused validators are those implementing an interface which
     * is not understood by this executor.
     * 
     * @return a map with all unused validators (key=validator id, value=actual validator)
     */
    public @NotNull Map<String, Validator> getUnusedValidatorsById() {
        Map<String, Validator> unusedValidators = new HashMap<>(validatorsById);
        unusedValidators.keySet().removeAll(documentViewXmlValidators.keySet());
        unusedValidators.keySet().removeAll(nodePathValidators.keySet());
        unusedValidators.keySet().removeAll(metaInfPathValidators.keySet());
        unusedValidators.keySet().removeAll(jcrPathValidators.keySet());
        unusedValidators.keySet().removeAll(genericJcrDataValidators.keySet());
        unusedValidators.keySet().removeAll(genericMetaInfDataValidators.keySet());
        // plus the ones bound to other validators
        unusedValidators.keySet().removeAll(filterValidators.keySet());
        unusedValidators.keySet().removeAll(propertiesValidators.keySet());
        return unusedValidators;
    }

    /** 
     * Validates a package META-INF input stream  with all relevant validators.
     * 
     * @param input the input stream (it is not closed during processing, this is obligation of the caller). Should not be buffered as buffering is done internally!
     * @param filePath should be relative to the META-INF directory (i.e. should not start with {@code META-INF})
     * @param basePath the path to which the file path is relative
     * @return the list of validation messages 
     * @throws IOException in case the input stream could not be accessed */
    public @NotNull Collection<ValidationViolation> validateMetaInf(@NotNull InputStream input, @NotNull Path filePath, @NotNull Path basePath) throws IOException {
        if (filePath.isAbsolute()) {
            throw new IllegalArgumentException("Given file path must not be absolute");
        }
        if (filePath.startsWith(Constants.META_INF)) {
            throw new IllegalArgumentException("Given file path must not start with META-INF but rather on the level below");
        }
        if (input == null) {
            throw new IllegalArgumentException("Given input stream must not be null!");
        }
        List<ValidationViolation> messages = new LinkedList<>();
        messages.add(new ValidationViolation(ValidationMessageSeverity.DEBUG, "Validating meta inf file '" + filePath + "'..."));
        messages.addAll(validateGenericMetaInfData(new EnhancedBufferedInputStream(input), filePath, basePath));
        return messages;
    }

    /** 
     * Validates a package jcr_root input stream  with all relevant validators.
     * 
     * @param input the input stream (it is not closed during processing, this is obligation of the caller). Should not be buffered as buffering is done internally!
     * @param filePath file path relative to the content package jcr root (i.e. the folder named "jcr_root")
     * @param basePath the path to which the file path is relative
     * @return the list of validation messages 
     * @throws IOException in case the input stream could not be accessed
     */
    public @NotNull Collection<ValidationViolation> validateJcrRoot(@NotNull InputStream input, @NotNull Path filePath, @NotNull Path basePath) throws IOException {
        if (filePath.isAbsolute()) {
            throw new IllegalArgumentException("Given path is not relative " + filePath);
        }
        if (filePath.startsWith(Constants.ROOT_DIR)) {
            throw new IllegalArgumentException("Given file path must not start with jcr_root but rather on the level below");
        }
        if (input == null) {
            throw new IllegalArgumentException("Given input stream must not be null!");
        }
        List<ValidationViolation> messages = new LinkedList<>();
        messages.add(new ValidationViolation(ValidationMessageSeverity.DEBUG, "Validating jcr file '" + filePath + "'..."));
        messages.addAll(validateGenericJcrData(new EnhancedBufferedInputStream(input), filePath, basePath));
        return messages;
    }

    /** 
     * Must be called at the end of the validation (when the validation context is no longer used).
     * This is important as some validators emit violation messages only when this method is called.
     * 
     * @return the list of additional validation violations (might be empty) which have not been reported before 
     */
    public @NotNull Collection<ValidationViolation> done() {
        Collection<ValidationViolation> allViolations = new LinkedList<>();
        // go through all validators (even the nested ones)
        for (Map.Entry<String, Validator>entry : validatorsById.entrySet()) {
            Collection<ValidationMessage> violations = entry.getValue().done();
            if (violations != null && !violations.isEmpty()) {
                allViolations.addAll(ValidationViolation.wrapMessages(entry.getKey(), violations, null, null, null, 0, 0));
            }
        }
        return allViolations;
    }

    private Collection<ValidationViolation> validateNodePaths(Path filePath, Path basePath, Map<String, Integer> nodePathsAndLineNumbers) {
        List<ValidationViolation> enrichedMessages = new LinkedList<>();
       
        for (Map.Entry<String, Integer> nodePathAndLineNumber : nodePathsAndLineNumbers.entrySet()) {
            for (Map.Entry<String, NodePathValidator> entry : nodePathValidators.entrySet()) {
                enrichedMessages.add(new ValidationViolation(entry.getKey(), ValidationMessageSeverity.DEBUG, "Validate..."));
                Collection<ValidationMessage> messages = entry.getValue().validate(nodePathAndLineNumber.getKey());
                if (messages != null && !messages.isEmpty()) {
                    enrichedMessages.addAll(ValidationViolation.wrapMessages(entry.getKey(), messages, filePath, basePath, nodePathAndLineNumber.getKey(),
                            nodePathAndLineNumber.getValue().intValue(), 0));
                }
            }
        }
        return enrichedMessages;
    }

    private Collection<ValidationViolation> validateGenericMetaInfData(InputStream input, Path filePath, Path basePath) throws IOException {
        Collection<ValidationViolation> enrichedMessages = new LinkedList<>();
        ResettableInputStream resettableInputStream = null;
        InputStream currentInput = input;
        for (Map.Entry<String, MetaInfPathValidator> entry : metaInfPathValidators.entrySet()) {
            Collection<ValidationMessage> messages = entry.getValue().validateMetaInfPath(filePath);
            if (messages != null && !messages.isEmpty()) {
                enrichedMessages.addAll(ValidationViolation.wrapMessages(entry.getKey(), messages, filePath, basePath, null, 0, 0));
            }
        }
        try {
            for (Map.Entry<String, GenericMetaInfDataValidator> entry : genericMetaInfDataValidators.entrySet()) {
                GenericMetaInfDataValidator validator = entry.getValue();
                if (validator.shouldValidateMetaInfData(filePath)) {
                    if (resettableInputStream == null) {
                        if (isAnotherValidatorFulfillingPathPredicate(genericMetaInfDataValidators.values(), GenericMetaInfDataValidator::shouldValidateMetaInfData, validator, filePath)) {
                            currentInput = resettableInputStream = new ResettableInputStream(input);
                        }
                    } else {
                        resettableInputStream.reset();
                    }
                    enrichedMessages.add(new ValidationViolation(entry.getKey(), ValidationMessageSeverity.DEBUG, "Validate..."));
                    Collection<ValidationMessage> messages = validator.validateMetaInfData(currentInput, filePath);
                    if (messages != null && !messages.isEmpty()) {
                        enrichedMessages.addAll(ValidationViolation.wrapMessages(entry.getKey(), messages, filePath, basePath, null, 0, 0));
                    }
                }
            }
        } finally {
            if (resettableInputStream != null) {
                resettableInputStream.close();
            }
        }
        return enrichedMessages;
    }

    private Collection<ValidationViolation> validateGenericJcrData(InputStream input, Path filePath, Path basePath) throws IOException {
        Map<String, Integer> nodePathsAndLineNumbers = new HashMap<>();
        Collection<ValidationViolation> enrichedMessages = new LinkedList<>();
        ResettableInputStream resettableInputStream = null;
        InputStream currentInput = input;
        for (Map.Entry<String, JcrPathValidator> entry : jcrPathValidators.entrySet()) {
            Collection<ValidationMessage> messages = entry.getValue().validateJcrPath(filePath);
            if (messages != null && !messages.isEmpty()) {
                enrichedMessages.addAll(ValidationViolation.wrapMessages(entry.getKey(), messages, filePath, basePath, null, 0, 0));
            }
        }
        try {
            // make sure the docviewparser always comes first
            for (Map.Entry<String, GenericJcrDataValidator> entry : genericJcrDataValidators.entrySet()) {
                GenericJcrDataValidator validator = entry.getValue();
                if (validator.shouldValidateJcrData(filePath)) {
                    if (resettableInputStream == null) {
                        if (isAnotherValidatorFulfillingPathPredicate(genericJcrDataValidators.values(), GenericJcrDataValidator::shouldValidateJcrData, validator, filePath)) {
                            currentInput = resettableInputStream = new ResettableInputStream(input);
                        }
                    } else {
                        resettableInputStream.reset();
                    }
                    enrichedMessages.add(new ValidationViolation(entry.getKey(), ValidationMessageSeverity.DEBUG, "Validate..."));
                    Collection<ValidationMessage> messages = validator.validateJcrData(currentInput, filePath, nodePathsAndLineNumbers);
                    if (messages != null && !messages.isEmpty()) {
                        enrichedMessages.addAll(ValidationViolation.wrapMessages(entry.getKey(), messages, filePath, basePath, null, 0, 0));
                    }
                } else {
                    // only do it if we haven't collected node paths from a previous run
                    if (nodePathsAndLineNumbers.isEmpty()) {
                        // convert file name to node path
                        String nodePath = filePathToNodePath(filePath);
                        log.debug("Found non-docview node '{}'", nodePath);
                        nodePathsAndLineNumbers.put(nodePath, 0);
                    }
                }
            }
        } finally {
            if (resettableInputStream != null) {
                resettableInputStream.close();
            }
        }
        enrichedMessages.addAll(validateNodePaths(filePath, basePath, nodePathsAndLineNumbers));
        return enrichedMessages;
    }

    /**
     * Converts the given file path (a relative one) to the absolute node path.
     * @param filePath the relative file path to convert
     * @return the node path
     */
    public static @NotNull String filePathToNodePath(@NotNull Path filePath) {
        // convert to forward slashes and make absolute by prefixing it with "/"
        String platformPath = "/" + FilenameUtils.separatorsToUnix(filePath.toString());
        return PlatformNameFormat.getRepositoryPath(platformPath, true);
    }

    private static <T extends Validator> boolean isAnotherValidatorFulfillingPathPredicate(Collection<T> validators, BiPredicate<T,Path> predicate, T currentValidator, Path filePath) {
        return validators.stream().filter(t -> !t.equals(currentValidator)).anyMatch(x -> predicate.test(x, filePath));
    }

    static <T> Map<String, T> filterValidatorsByClass(Map<String, Validator> allValidators, Class<T> type) {
        return allValidators.entrySet().stream()
                .filter(x -> type.isInstance(x.getValue()))
                .collect(Collectors.toMap(Map.Entry::getKey, x -> type.cast(x.getValue())));
    }

}