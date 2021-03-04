import React, {useEffect, useState} from "react";
import {ArchiveEntry} from "../types";
import {Grid, makeStyles, Paper} from "@material-ui/core";
import FlexMetadataEntry from "./FlexMetadataEntry";
import PathDisplayComponent from "../browse/PathDisplayComponent";
import {Storage} from "@material-ui/icons";
import {extractFileInfo} from "../common/Fileinfo";
import Chip from '@material-ui/core/Chip';
import MediaDurationComponent from "../common/MediaDurationComponent";
import FileSizeView from "../Entry/FileSizeView";

interface FlexMetadataProps {
    entry: ArchiveEntry
    jumpToPath?: ()=>void;
}

const useStyles = makeStyles((theme)=>({
    metadataEntry: {
        margin: "1em"
    },
    chip: {
        margin: theme.spacing(0.5)
    },
    chipContainer: {
        margin: 0,
        padding: 0
    }
}));

const FlexMetadata:React.FC<FlexMetadataProps> = (props) => {
    const [firstVideoStream, setFirstVideoStream] = useState<any|undefined>(undefined);
    const [firstAudioStream, setFirstAudioStream] = useState<any|undefined>(undefined);
    const [videoStreamCount, setVideoStreamCount] = useState(0);
    const [audioStreamCount, setAudioStreamCount] = useState(0);
    const classes = useStyles();

    const fileInfo = extractFileInfo(props.entry.path);

    /**
     * cache some information that is more expensive to obtain
     */
    useEffect(()=>{
        if(props.entry.mediaMetadata ) {
            const vStreams = props.entry.mediaMetadata.streams.filter(entry => entry.codec_type === "video");
            const aStreams = props.entry.mediaMetadata.streams.filter(entry => entry.codec_type === "audio");
            setFirstVideoStream(vStreams.length > 0 ? vStreams[0] : undefined);
            setFirstAudioStream(aStreams.length > 0 ? aStreams[0] : undefined);
            setVideoStreamCount(vStreams.length);
            setAudioStreamCount(aStreams.length);
        } else {
            setFirstAudioStream(undefined);
            setFirstAudioStream(undefined);
            setVideoStreamCount(0);
            setAudioStreamCount(0);
        }
    }, [props.entry]);

    return <>
        <Grid container>
            {
                //FIXME: should put functionality on browse to open a specific set of paths
            }

            <FlexMetadataEntry className={classes.metadataEntry}
                               label="Path"
                               value={<PathDisplayComponent path={fileInfo.filepath}/>}
                               callout={props.jumpToPath}
            />
            <FlexMetadataEntry className={classes.metadataEntry} label="Collection" value={props.entry.bucket} icon={<Storage/>}/>
            <FlexMetadataEntry className={classes.metadataEntry} label="Storage class" value={props.entry.storageClass}/>
            <FlexMetadataEntry className={classes.metadataEntry} label="Region" value={props.entry.region ?? "(not set)"}/>
            {
                props.entry.mediaMetadata ? <>
                    <FlexMetadataEntry className={classes.metadataEntry}
                                       label="Duration"
                                       value={<MediaDurationComponent value={props.entry.mediaMetadata.format.duration.toString()}/>}
                    />
                    <FlexMetadataEntry className={classes.metadataEntry} label="Bit rate" value={props.entry.mediaMetadata.format.bit_rate.toString()}/>
                    <FlexMetadataEntry className={classes.metadataEntry} label="Format" value={props.entry.mediaMetadata.format.format_long_name}/>
                    <FlexMetadataEntry className={classes.metadataEntry}
                                       label="File size"
                                       value={<FileSizeView rawSize={props.entry.mediaMetadata.format.size}/>}
                    />
                    <FlexMetadataEntry className={classes.metadataEntry} label="Format tags" value={
                        <Grid container className={classes.chipContainer}>
                        {
                            Object.keys(props.entry.mediaMetadata.format.tags)
                                .map(k=><Grid item>
                                    <Chip
                                        label={`${k}: ${props.entry.mediaMetadata?.format.tags[k]}`}
                                        key={k}
                                        className={classes.chip}
                                        />
                                </Grid>)
                        }
                        </Grid>
                    }/>
                </> : undefined
            }
            <FlexMetadataEntry className={classes.metadataEntry}
                               label="Channels"
                               value={<span>{videoStreamCount} video, {audioStreamCount} audio</span>}
            />
            { firstVideoStream || firstAudioStream ?
                <FlexMetadataEntry className={classes.metadataEntry}
                                   label="Codecs"
                                   value={<span>{firstVideoStream ? firstVideoStream.codec_name : "(none)"} / {firstAudioStream ? firstAudioStream.codec_name : "(none)"}</span>}
                /> : undefined
            }
            {
                firstVideoStream ?
                    <FlexMetadataEntry className={classes.metadataEntry}
                                       label="Resolution"
                                       value={<span>{firstVideoStream.width} x {firstVideoStream.height}</span> }/>
                    : undefined
            }
            {
                firstAudioStream ?
                    <FlexMetadataEntry className={classes.metadataEntry}
                                       label="Audio"
                                       value={firstAudioStream.channel_layout}/> : undefined
            }
        </Grid>
        </>
}

export default FlexMetadata;