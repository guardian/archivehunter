import React, {useEffect, useState} from "react";
import {MimeType, ProxyLocation, ProxyLocationsResponse, ProxyType, StylesMap} from "../types";
import axios from "axios";
import {formatError} from "../common/ErrorViewComponent";
import {Button, CircularProgress, makeStyles, Typography} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import EntryPreviewSwitcher from "./EntryPreviewSwitcher";
import clsx from "clsx";
import MediaPlayer from "./MediaPlayer";
import EntryThumbnail from "./EntryThumbnail";

interface MediaPreviewProps {
    onError?: (errorString:string)=>void;
    triggeredProxyGeneration?: ()=>void;
    itemId: string;
    fileExtension: string;
    mimeType: MimeType;
}

const useStyles = makeStyles((theme)=>Object.assign({
    partialDivider: {
        width: "70%",
    },
    errorText: {
        color: theme.palette.error.dark,
        fontWeight: "bold"
    },
    processingText: {
        color: theme.palette.success,
        fontStyle: "italic"
    },
    thumbNote: {
        marginTop: 0,
        marginBottom: 0,
        fontStyle: "italic"
    }
} as StylesMap, baseStyles));

const MediaPreview:React.FC<MediaPreviewProps> = (props) => {
    const [proxyLocations, setProxyLocations] = useState<ProxyLocation[]>([]);
    const [loading, setLoading] = useState(true);
    const [selectedPreview, setSelectedPreview] = useState<ProxyType>("VIDEO");
    const [processMessage, setProcessMessage] = useState<string|undefined>(undefined);
    const [autoPlay, setAutoPlay] = useState(true);
    const [showingCreate, setShowingCreate] = useState(false);

    const classes = useStyles();

    useEffect(()=>{
        const loadData = async ()=> {
            try {
                const response = await axios.get<ProxyLocationsResponse>(`/api/proxy/${props.itemId}`);
                setProxyLocations(response.data.entries);
                setLoading(false);
            } catch(err) {
                setLoading(false);
                if(props.onError) props.onError(formatError(err, false));
            }
        }

        loadData();
    }, [props.itemId]);

    const bestAvailablePreview = (proxyTypes:ProxyType[])=>{
        if(proxyTypes.includes("VIDEO")) return "VIDEO";
        if(proxyTypes.includes("AUDIO")) return "AUDIO";
        if(proxyTypes.includes("POSTER")) return "POSTER";
        if(proxyTypes.includes("THUMBNAIL")) return "THUMBNAIL";
        return "THUMBNAIL";
    }

    useEffect(()=>{
        const proxyTypes = proxyLocations.map(loc=>loc.proxyType);
        setSelectedPreview(bestAvailablePreview(proxyTypes))
    }, [proxyLocations]);

    const newTypeSelected = (newType:ProxyType)=> {
        const proxyTypes = proxyLocations.map(loc=>loc.proxyType);
        setShowingCreate(!proxyTypes.includes(newType));
        setSelectedPreview(newType)
    }

    const initiateCreateProxy = async ()=>{
        try {
        const result = await axios.post("/api/proxy/generate/" + props.itemId + "/" + selectedPreview.toLowerCase());
            const msg = result.data.entry==="disabled" ? "Proxy generation disabled for this storage" : "Proxy generation started";
            setProcessMessage(msg);
            if(props.triggeredProxyGeneration && result.data.entry!=="disabled") props.triggeredProxyGeneration();
        } catch(err) {
            console.log(err);
            setProcessMessage("Proxy generation failed, see console log");
            if(props.onError) props.onError(formatError(err, false));
        }
    }

    const handleProxyNotFound = (proxyType:ProxyType) => {
        setShowingCreate(true);

    }

    if(loading) {
        return <div className={classes.centered}>
            <CircularProgress/>
        </div>
    }

    return <div className={classes.centered}>
        {
            showingCreate ? <>
                <EntryThumbnail mimeType={props.mimeType} fileExtension={props.fileExtension} entryId={props.itemId}/>
                <p className={classes.thumbnote}>There is no {selectedPreview} proxy available</p>
                <Button className={classes.centered}
                        style={{marginTop: "0.4em"}}
                        variant="outlined"
                        onClick={initiateCreateProxy}>
                    Create {selectedPreview}
                </Button>
            </> : <MediaPlayer entryId={props.itemId}
                               onError={props.onError}
                               mimeType={props.mimeType}
                               playableType={selectedPreview}
                               onProxyNotFound={handleProxyNotFound}
                               autoPlay={autoPlay}/>
        }
        <hr className={classes.partialDivider}/>
        <EntryPreviewSwitcher availableTypes={proxyLocations.map(loc=>loc.proxyType).join(",")} typeSelected={newTypeSelected}/>
        { processMessage ? <Typography className={clsx(classes.processingText, classes.centered)}>{processMessage}</Typography> : ""}
        <hr className={classes.partialDivider}/>
    </div>
}

export default MediaPreview;