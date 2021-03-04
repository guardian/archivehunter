import React, {useEffect, useState} from "react";
import {makeStyles} from "@material-ui/core";
import axios from "axios";
import {PlayableProxyResponse, ProxyType} from "../types";
import {formatError} from "../common/ErrorViewComponent";
import {nillComparer} from "@material-ui/data-grid";

interface MediaPlayerProps {
    mimeType?: { major: string, minor: string };
    entryId: string;
    playableType: ProxyType;
    onError?: (errorString:string)=>void;        //called if something goes wrong, e.g. REST error, for parent(s) to display error
    onProxyNotFound?: (proxyType:ProxyType)=>void;  //called if the proxy gives us a 404
    autoPlay: boolean;
}

const useStyles = makeStyles((theme)=>({
    videoPreview: {
        marginLeft: "1em",
        marginRight: "1em",
        minWidth: "360px",
        maxWidth: "1920px",
        maxHeight: "1080px",
        minHeight: "180px",
        height: "50%",
        boxSizing: "border-box",
        display: "flex",
        "& video": {
            flex: "0 1 100%",
            objectFit: "fill",
        }
    },
    thumbnailPreview: {
        width: "95%",
        marginLeft: "auto",
        marginRight: "auto",
        display: "block"
    },
    audioPlayer: {
        width: "640px",
        height: "360px",
        backgroundSize: "cover",
        "& audio": {
            position: "absolute",
            bottom: 0,
            width: "100%"
        }
    },
    errorText: {
        color: theme.palette.error.dark,
        fontWeight: "bold"
    },
}));

const MediaPlayer:React.FC<MediaPlayerProps> = (props) => {
    const [previewData, setPreviewData] = useState<PlayableProxyResponse|undefined>(undefined);
    const classes = useStyles();

    useEffect(()=>{
        const loadPlayableData = async ()=> {
            try {
                const response = await axios.get<PlayableProxyResponse>(
                    `/api/proxy/${props.entryId}/playable?proxyType=${props.playableType}`,
                    {
                        validateStatus: (status)=>status==200||status==404
                    }
                );
                if(response.status==200) {
                    setPreviewData(response.data);
                } else if(response.status==404) {
                    setPreviewData(undefined);
                    if(props.onProxyNotFound) props.onProxyNotFound(props.playableType);
                } else {
                    console.error("MediaPlayer got unexpected status ", response.status, " this should not happen");
                }
            } catch (err) {
                if (props.onError) props.onError(formatError(err, false));
            }
        }
        loadPlayableData();
    }, [props.entryId]);

    return previewData ? <>
        {
            previewData?.mimeType.major==="video" ?
                <div className={classes.videoPreview}>
                    <video className={classes.videoPreview} src={previewData.uri} controls={true} autoPlay={props.autoPlay}/>
                </div> :
                null
        }
        {
            previewData?.mimeType.major==="audio" ?
                <div className={classes.audioPlayer}>
                    <audio src={previewData.uri} controls={true} autoPlay={props.autoPlay} />
                </div> :
                null
        }
        {
            previewData?.mimeType.major==="image" ?
                <img src={previewData.uri} alt="Thumbnail" className={classes.thumbnailPreview}/> :
                null
        }
        </> : <span className={classes.errorText}>No preview data</span>
}

export default MediaPlayer;