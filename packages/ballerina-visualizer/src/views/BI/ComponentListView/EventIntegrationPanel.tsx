/**
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com) All Rights Reserved.
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Codicon, Icon, SearchBox } from '@wso2/ui-toolkit';
import { useRpcContext } from '@wso2/ballerina-rpc-client';
import { DIRECTORY_MAP, EVENT_TYPE, MACHINE_VIEW, TriggerModelsResponse, ServiceModel, SCOPE } from '@wso2/ballerina-core';
import debounce from 'lodash.debounce';

import { CardGrid, PanelViewMore, Title, TitleWrapper } from './styles';
import { BodyText } from '../../styles';
import ButtonCard from '../../../components/ButtonCard';
import { isBetaModule, OutOfScopeComponentTooltip } from './componentListUtils';
import { RelativeLoader } from '../../../components/RelativeLoader';

interface EventIntegrationPanelProps {
    scope: SCOPE;
    triggers: TriggerModelsResponse;
};

const SEARCH_DEBOUNCE_MS = 700;

export function EventIntegrationPanel(props: EventIntegrationPanelProps) {
    const { rpcClient } = useRpcContext();
    const isDisabled = props.scope && (props.scope !== SCOPE.EVENT_INTEGRATION && props.scope !== SCOPE.ANY);

    const [showSearch, setShowSearch] = useState<boolean>(false);
    const [query, setQuery] = useState<string>("");
    const [searching, setSearching] = useState<boolean>(false);
    const [results, setResults] = useState<ServiceModel[]>([]);

    const handleClick = async (key: DIRECTORY_MAP, model: ServiceModel) => {
        await rpcClient.getVisualizerRpcClient().openView({
            type: EVENT_TYPE.OPEN_VIEW,
            location: {
                view: MACHINE_VIEW.BIServiceWizard,
                artifactInfo: {
                    org: model.orgName,
                    packageName: model.packageName,
                    moduleName: model.moduleName,
                    version: model.version
                }
            },
        });
    };

    // Discover event triggers from Ballerina Central. Debounced so we don't hit Central on every keystroke.
    const runSearch = useMemo(
        () =>
            debounce((searchQuery: string) => {
                setSearching(true);
                rpcClient
                    .getServiceDesignerRpcClient()
                    .searchTriggers({ query: searchQuery })
                    .then((res) => {
                        setResults(res?.local ?? []);
                    })
                    .finally(() => {
                        setSearching(false);
                    });
            }, SEARCH_DEBOUNCE_MS),
        [rpcClient]
    );

    useEffect(() => {
        return () => runSearch.cancel();
    }, [runSearch]);

    const onQueryChange = (value: string) => {
        setQuery(value);
        runSearch(value);
    };

    const openSearch = () => {
        setShowSearch(true);
        // Prime the list with popular triggers from Central (empty query -> curated defaults).
        if (results.length === 0) {
            runSearch("");
        }
    };

    const localTriggerIds = new Set(props.triggers.local.map((t) => `${t.orgName}/${t.packageName}`));

    return (
        <PanelViewMore disabled={isDisabled}>
            <TitleWrapper>
                <Title variant="h2">Event Integration</Title>
                <BodyText>
                    Create an integration that can be triggered by an event.
                </BodyText>
            </TitleWrapper>
            <CardGrid>
                {props.triggers.local.length === 0 && <RelativeLoader />}
                {
                    props.triggers.local
                        .filter((t) => t.type === "event")
                        .map((item, index) => {
                            return (
                                <ButtonCard
                                    id={`trigger-${item.moduleName.replace(/\./g, '-')}`}
                                    key={item.id}
                                    title={item.name}
                                    icon={getEntryNodeIcon(item)}
                                    onClick={() => {
                                        handleClick(DIRECTORY_MAP.SERVICE, item);
                                    }}
                                    disabled={isDisabled}
                                    tooltip={isDisabled ? OutOfScopeComponentTooltip : ""}
                                    isBeta={isBetaModule(item.moduleName)}
                                />
                            );
                        }
                        )
                }
                {!isDisabled && !showSearch && (
                    <ButtonCard
                        id="trigger-search-more"
                        title="Search more"
                        icon={<Codicon name="search" />}
                        onClick={openSearch}
                    />
                )}
            </CardGrid>
            {showSearch && (
                <div style={{ marginTop: 12 }}>
                    <SearchBox
                        placeholder="Search event triggers on Ballerina Central"
                        value={query}
                        onChange={onQueryChange}
                        iconPosition="end"
                        aria-label="search-event-triggers"
                        data-testid="trigger-search-input"
                        sx={{ width: '100%' }}
                    />
                    <CardGrid style={{ marginTop: 12 }}>
                        {searching && <RelativeLoader />}
                        {!searching && results.length === 0 && (
                            <BodyText>No triggers found on Central for this search.</BodyText>
                        )}
                        {!searching &&
                            results
                                // Central may echo a package already available locally; hide duplicates.
                                .filter((item) => !localTriggerIds.has(`${item.orgName}/${item.packageName}`))
                                .map((item) => (
                                    <ButtonCard
                                        id={`central-trigger-${item.moduleName.replace(/\./g, '-')}`}
                                        key={`${item.orgName}/${item.packageName}`}
                                        title={item.name}
                                        icon={getEntryNodeIcon(item)}
                                        onClick={() => handleClick(DIRECTORY_MAP.SERVICE, item)}
                                        disabled={isDisabled}
                                        isBeta={isBetaModule(item.moduleName)}
                                    />
                                ))}
                    </CardGrid>
                </div>
            )}
        </PanelViewMore>
    );
};

// TODO: This should be removed once the new icons are added to the BE API.
export function getEntryNodeIcon(item: ServiceModel) {
    return getCustomEntryNodeIcon(item.moduleName) || <img src={item.icon} alt={item.name} style={{ width: "38px" }} />;
}

// INFO: This is a temporary function to get the custom icon for the entry points.
// TODO: This should be removed once the new icons are added to the BE API.
export function getCustomEntryNodeIcon(type: string) {
    switch (type) {
        case "tcp":
            return <Icon name="bi-tcp" />;
        case "kafka":
            return <Icon name="bi-kafka" />;
        case "rabbitmq":
            return <Icon name="bi-rabbitmq" sx={{ color: "#f60" }} />;
        case "nats":
            return <Icon name="bi-nats" />;
        case "mqtt":
            return <Icon name="bi-mqtt" sx={{ color: "#606" }} />;
        case "grpc":
            return <Icon name="bi-grpc" />;
        case "graphql":
            return <Icon name="bi-graphql" sx={{ color: "#e535ab" }} />;
        case "java.jms":
            return <Icon name="bi-java" />;
        case "trigger.github":
            return <Icon name="bi-github" />;
        case "mcp":
            return <Icon name="bi-mcp" />;
        case "solace":
            return <Icon name="bi-solace" sx={{ color: "#00C895" }}/>;
        case "mssql":
            return <Icon name="bi-mssql" sx={{ color: "#b61d1c" }}/>;
        case "postgresql":
            return <Icon name="bi-postgresql" sx={{ color: "#336791" }}/>;
        case "mysql":
            return <Icon name="bi-mysql" sx={{ color: "#00678c" }}/>;
        case "trigger.shopify":
            return <Icon name="bi-shopify" sx={{ color: "#95BF47" }} />;
        default:
            return null;
    }
}
