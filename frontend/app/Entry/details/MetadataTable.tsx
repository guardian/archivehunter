import React, {useState, useEffect} from "react";
import MediaDurationComponent from "../../common/MediaDurationComponent";
import FileSizeView from "../FileSizeView";
import {ArchiveEntry} from "../../types";
import {IconButton, makeStyles} from "@material-ui/core";
import clsx from "clsx";
import PathDisplayComponent from "../../browse/PathDisplayComponent";
import {FileInfo, extractFileInfo} from "../../common/Fileinfo";
import {Launch} from "@material-ui/icons";

interface MetadataTableProps {
    entry: ArchiveEntry;
    tableRowsInsert?: React.ReactFragment[];
    openClicked?: (itemId:string)=>void;
}

const useStyles = makeStyles({
    metadataTable: {
        marginTop: "1em",
        border: "none"
    },
    metadataHeading: {
        fontWeight: "bold",
        border: "none",
        textAlign: "right",
        paddingRight: "0.8em",
        paddingTop: 0,
        paddingBottom: 0
    },
    metadataEntry: {
        border: "none",
        textAlign: "left",
        paddingTop: 0,
        paddingBottom: 0
    },
    spaceBelow: {
        paddingBottom: "0.8em"
    }
});


const MetadataTable:React.FC<MetadataTableProps> = (props) => {
    const [firstVideoStream, setFirstVideoStream] = useState<any|undefined>(undefined);
    const [firstAudioStream, setFirstAudioStream] = useState<any|undefined>(undefined);
    const [videoStreamCount, setVideoStreamCount] = useState(0);
    const [audioStreamCount, setAudioStreamCount] = useState(0);

    const classes = useStyles();

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

    const fileinfo:FileInfo = extractFileInfo(props.entry.path);

    return <table className={classes.metadataTable}>
        <tbody>
        {
            props.openClicked ? <tr>
                <td className={clsx(classes.metadataHeading, classes.spaceBelow)}>Open</td>
                <td className={classes.metadataEntry}>
                    <IconButton onClick={()=>props.openClicked ? props.openClicked(props.entry.id) : null}>
                        <Launch/>
                    </IconButton>
                </td>
            </tr> : null
        }
        <tr>
            <td className={clsx(classes.metadataHeading, classes.spaceBelow)}>Name</td>
            <td className={classes.metadataEntry}>{fileinfo.filename}</td>
        </tr>
        <tr>
            <td className={clsx(classes.metadataHeading, classes.spaceBelow)}>Catalogue</td>
            <td className={classes.metadataEntry}>{props.entry.bucket}</td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Channels</td>
            <td className={classes.metadataEntry}>
                <span>{videoStreamCount} video, {audioStreamCount} audio</span>
            </td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Format</td>
            <td className={classes.metadataEntry}>
                <span>{firstVideoStream ? firstVideoStream.codec_name : "(none)"} / {firstAudioStream ? firstAudioStream.codec_name : "(none)"}</span>
            </td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Resolution</td>
            <td className={classes.metadataEntry}>{
                firstVideoStream ? <span>{firstVideoStream.width} x {firstVideoStream.height}</span> :
                    <p className="information dont-expand">no data</p>
            }</td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Duration</td>
            <td className={classes.metadataEntry}>{
                props.entry.mediaMetadata && props.entry.mediaMetadata.format ?
                    <MediaDurationComponent value={props.entry.mediaMetadata.format.duration}/> :
                    <p className="information dont-expand">no data</p>
            }</td>
        </tr>

        <tr>
            <td className={clsx(classes.metadataHeading, classes.spaceBelow)}>Audio</td>
            <td className={classes.metadataEntry}>{
                firstAudioStream ? firstAudioStream.channel_layout :
                    <p className="information dont-expand">no data</p>
            }</td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>File path</td>
            <td className={classes.metadataEntry}>{fileinfo.filepath==="" ? <i>root</i> : <PathDisplayComponent path={fileinfo.filepath}/>}</td>
        </tr>

        <tr>
            <td className={clsx(classes.metadataHeading, classes.spaceBelow)}>File size</td>
            <td className={classes.metadataEntry}><FileSizeView rawSize={props.entry.size}/></td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Data type</td>
            <td className={classes.metadataEntry}>{props.entry.mimeType.major}/{props.entry.mimeType.minor}</td>
        </tr>
        <tr>
            <td className={classes.metadataHeading}>Storage class</td>
            <td className={classes.metadataEntry}>{props.entry.storageClass}</td>
        </tr>
        { props.tableRowsInsert ? props.tableRowsInsert : null }
        </tbody>
    </table>
}

export {extractFileInfo};
export default MetadataTable;