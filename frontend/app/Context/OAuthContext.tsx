import React, { useEffect, useState } from "react";

interface OAuthContextData {
  clientId: string;
  resource: string;
  oAuthUri: string;
  tokenUri: string;
  redirectUri: string;
}

const OAuthContext = React.createContext<OAuthContextData | undefined>(
  undefined
);

const OAuthContextProvider: React.FC<{
  children: React.ReactFragment;
  onError?: (desc: string) => void;
}> = (props) => {
  const [clientId, setClientId] = useState("");
  const [resource, setResource] = useState("");
  const [oAuthUri, setOAuthUri] = useState("");
  const [tokenUri, setTokenUri] = useState("");
  const [haveData, setHaveData] = useState(false);

  const currentUri = new URL(window.location.href);
  const redirectUrl =
    currentUri.protocol + "//" + currentUri.host + "/oauth2/callback";

  const loadOauthData = async () => {
    const response = await fetch("/meta/oauth/config.json");
    switch (response.status) {
      case 200:
        const content = await response.json();

        setClientId(content.clientId);
        setResource(content.resource);
        setOAuthUri(content.oAuthUri);
        setTokenUri(content.tokenUri);
        setHaveData(true);
        break;
      case 404:
        await response.text(); //consume body and discard it
        if (props.onError)
          props.onError(
            "Metadata not found on server, please contact administrator"
          ); //temporary until we have global snackbar
        break;
      default:
        await response.text(); //consume body and discard it
        if (props.onError)
          props.onError(
            `Server returned a ${response.status} error trying to access meetadata`
          );
        break;
    }
  };

  useEffect(() => {
    loadOauthData();
  }, []);

  return (
    <OAuthContext.Provider
      value={
        haveData
          ? {
              clientId: clientId,
              resource: resource,
              oAuthUri: oAuthUri,
              tokenUri: tokenUri,
              redirectUri: redirectUrl,
            }
          : undefined
      }
    >
      {props.children}
    </OAuthContext.Provider>
  );
};

function makeLoginUrl(oAuthContext: OAuthContextData) {
  const args = {
    response_type: "code",
    client_id: oAuthContext.clientId,
    resource: oAuthContext.resource,
    redirect_uri: oAuthContext.redirectUri,
    state: "/",
  };

  const encoded = Object.entries(args).map(
      ([k, v]) => `${k}=${encodeURIComponent(v)}`
  );

  return oAuthContext.oAuthUri + "?" + encoded.join("&");
}

export type { OAuthContextData };

export {OAuthContext, OAuthContextProvider, makeLoginUrl};
