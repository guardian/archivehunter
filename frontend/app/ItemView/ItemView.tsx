import React, {useEffect, useState} from "react";
import {RouteComponentProps} from "react-router";
import {ArchiveEntry, ObjectGetResponse, UserResponse} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import {CircularProgress, makeStyles, Paper, Snackbar} from "@material-ui/core";
import Helmet from "react-helmet";
import MuiAlert from "@material-ui/lab/Alert";
import clsx from "clsx";
import FlexMetadata from "./FlexMetadata";
import MediaPreview from "../Entry/MediaPreview";
import LightboxInsert from "../Entry/details/LightboxInsert";
import ItemActions from "./ItemActions";
import {extractFileInfo} from "../common/Fileinfo";

interface ItemViewParams {
    id: string;
}

const useStyles = makeStyles((theme)=>({
    itemWindow: {
        display: "flex",
        flexDirection: "column",
        height: "95vh"
    },
    previewArea: {
        flexGrow: 1,
        flexShrink: 1,
    },
    infoArea: {
        flex: 1,
        padding: "1em",
    },
    centered: {
        marginLeft: "auto",
        marginRight: "auto",
    },
    inlineThrobber: {
        marginRight: "1em"
    },
    loading: {
        verticalAlign: "baseline"
    },
    title: {
        marginLeft: "0.4em"
    },
}));

const ItemView:React.FC<RouteComponentProps<ItemViewParams>> = (props) => {
    const [entry, setEntry] = useState<ArchiveEntry|undefined>(undefined);
    const [loading, setLoading] = useState(true);
    const [lastError, setLastError] = useState<string|undefined>(undefined);
    const [lastInfo, setLastInfo] = useState<string|undefined>(undefined);
    const [showingAlert, setShowingAlert] = useState(false);
    const [userLogin, setUserLogin] = useState<UserResponse|undefined>(undefined);

    const classes = useStyles();

    useEffect(()=>{
        const loadUser = async () => {
            try {
                const response = await axios.get<UserResponse>(`/api/loginStatus`);
                setUserLogin(response.data);
            } catch(err) {
                console.error("Could not get current logged in user: ", err);
                setLastError(formatError(err, false));
                setShowingAlert(true);
            }
        }
        loadUser();
    }, []);

    const loadEntry = async (entryId:string) => {
        try {
            const response = await axios.get<ObjectGetResponse<ArchiveEntry>>(`/api/entry/${entryId}`);
            setEntry(response.data.entry);
            setLoading(false);
        } catch (err) {
            console.error("Could not load item ", entryId, ": ", err);
            setLoading(false);
            setLastError(formatError(err, false));
            setShowingAlert(true);
        }
    }

    useEffect(()=>{
        if(props.match.params.id && props.match.params.id!="") {
            loadEntry(props.match.params.id);
        } else {
            setLoading(false);
            setLastError("Nothing given to load");
            setShowingAlert(true);
        }
    }, []);

    const closeAlert = ()=> {
        setShowingAlert(false);
    }

    const proxyGenerationWasTriggered = () => {
        setLastInfo("Started proxy generation");
        setShowingAlert(true);
    }

    const subComponentError = (errorDesc:string) => {
        setLastError(errorDesc);
        setShowingAlert(true);
    }

    const isInLightbox = () => {
        if(!entry || !userLogin) return false;
        const matchingEntries = entry.lightboxEntries.filter(lbEntry=>lbEntry.owner===userLogin.email);
        return matchingEntries.length>0;
    }

    const itemWasLightboxed = (itemId:string) => {
        return new Promise<void>((resolve, reject)=> {
            window.setTimeout(() => {
                setLoading(true);
                loadEntry(itemId)
                    .then(()=>resolve())
                    .catch((err)=>{
                        setLastError(err);
                        setShowingAlert(true);
                        reject(err);
                    })
            }, 1000);   //if we load immediately the server may not have processed the lightboxing yet
        });

    }

    const fileInfo = entry ? extractFileInfo(entry.path) : undefined;

    return <div className={classes.itemWindow}>
        <Helmet>
            <title>{
                fileInfo ? `${fileInfo.filename} - Archive Hunter` : "Archive Hunter"
            }</title>
        </Helmet>
        <Snackbar open={showingAlert} onClose={closeAlert} autoHideDuration={8000}>
            <>
            {
                lastError ? <MuiAlert severity="error" onClose={closeAlert}>{lastError}</MuiAlert> : undefined
            }
            {
                lastInfo ? <MuiAlert severity="info" onClose={closeAlert}>{lastInfo}</MuiAlert> : undefined
            }
            </>
        </Snackbar>
        <div className={classes.previewArea}>
            {entry ?
                <MediaPreview itemId={entry.id}
                              itemName={entry.path}
                              mimeType={entry.mimeType}
                              fileExtension={entry.file_extension ?? ".dat"}
                              triggeredProxyGeneration={proxyGenerationWasTriggered}
                              onError={subComponentError}
                              /> : undefined }
            {
                loading ?
                    <span className={clsx(classes.centered, classes.loading)}><CircularProgress className={classes.inlineThrobber}/>Loading...</span> :
                    undefined
            }
            {
                entry ? <LightboxInsert isInLightbox={isInLightbox()}
                                        entryId={entry.id}
                                        lightboxEntries={entry?.lightboxEntries ?? []}
                                        onError={subComponentError}
                                        lightboxedCb={itemWasLightboxed}
                    /> : undefined
            }
        </div>

        <div className={classes.infoArea}>
            <Paper elevation={3} style={{height: "100%"}}>
                {
                    fileInfo ? <h1 className={classes.title}>{fileInfo.filename}</h1> : undefined
                }
                {
                    entry ? <ItemActions storageClass={entry.storageClass}
                                         isInLightbox={isInLightbox()}
                                         itemId={entry.id}
                                         lightboxedCb={itemWasLightboxed}
                                         onError={subComponentError}
                                         /> : undefined
                }
                {
                    entry ? <FlexMetadata entry={entry}/> : undefined
                }
            </Paper>
        </div>
    </div>
}

export default ItemView;