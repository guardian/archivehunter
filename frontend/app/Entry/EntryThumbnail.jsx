import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import {createStyles, withStyles} from "@material-ui/core";
import clsx from "clsx";

const styles = createStyles({
    entryThumbnail: {
        color: "inherit",
        marginLeft: "auto",
        marginRight: "auto",
        overflow: "hidden",
        marginTop: "auto",
        marginBottom: "auto",
        height: "80px",
        display: "block"
    },
    entryThumbnailShadow: {
        boxShadow: "2px 2px 6px black"
    }
});

class EntryThumbnail extends React.Component {
    static propTypes = {
        mimeType: PropTypes.object.isRequired,
        fileExtension: PropTypes.string,
        entryId: PropTypes.string.isRequired,
        dataTip: PropTypes.string,
        cancelToken: PropTypes.object
    };

    static knownAudioExtensions = [
        "wav",
        "aiff",
        "aif",
        "mp3",
        "m4a",
        "m3a"
    ];

    static knownVideoExtensions = [
        "mpg",
        "mp4",
        "m4v",
        "wmv",
        "mov",
        "avi",
        "mkv"
    ];

    static knownImageExtensions = [
        "jpg",
        "jpeg",
        "tif",
        "tiff",
        "tga",
        "png",
        "pict",
        "pct"
    ];

    constructor(props){
        super(props);

        this.state = {
            thumbnailUri: null
        }
    }

    componentDidMount(){
        this.setState({loading: true, lastError: null},()=>{
            axios.get("/api/proxy/" + this.props.entryId + "/playable?proxyType=THUMBNAIL", {cancelToken: this.props.cancelToken})
                .then(result=>{
                    this.setState({loading: false, lastError: null, thumbnailUri: result.data.uri})
                }).catch(err=>{
                    if(!axios.isCancel(err)) {
                        this.setState({loading: false, lastError: err})
                    }
                })
        })
    }

    /**
     * deliver a "sensible" icon based on file extension, if the index does not provide us with an adequate MIME type
     */
    iconFromExtension(){
        //no file extension at all=>default icon
        if(!this.props.fileExtension) return <FontAwesomeIcon icon="file" size="4x" className={this.props.classes.entryThumbnail}/>;
        const lcXtn = this.props.fileExtension.toLowerCase();

        if(EntryThumbnail.knownAudioExtensions.includes(lcXtn)) return <FontAwesomeIcon icon="volume-up" size="4x" className={this.props.classes.entryThumbnail}/>;
        if(EntryThumbnail.knownVideoExtensions.includes(lcXtn)) return <FontAwesomeIcon icon="film" size="4x" className={this.props.classes.entryThumbnail}/>;
        if(EntryThumbnail.knownImageExtensions.includes(lcXtn)) return <FontAwesomeIcon icon="image" size="4x" className={this.props.classes.entryThumbnail}/>;
        return <FontAwesomeIcon icon="file" size="4x" className={this.props.classes.entryThumbnail}/>;
    }

    render(){
        if(this.state.thumbnailUri) return <img src={this.state.thumbnailUri} className={clsx(this.props.classes.entryThumbnail, this.props.classes.entryThumbnailShadow)}/>;

        if(!this.props.mimeType) return this.iconFromExtension();

        if(this.props.mimeType.major==="application" && this.props.mimeType.minor==="octet-stream") return this.iconFromExtension();
        if(this.props.mimeType.major==="video") return <FontAwesomeIcon icon="film" size="4x" className={this.props.classes.entryThumbnail}/>;
        if(this.props.mimeType.major==="audio") return <FontAwesomeIcon icon="volume-up" size="4x" className={this.props.classes.entryThumbnail}/>;
        if(this.props.mimeType.major==="image") return <FontAwesomeIcon icon="image" size="4x" className={this.props.classes.entryThumbnail}/>;
        return <FontAwesomeIcon icon="file" size="4x" className={this.props.classes.entryThumbnail}/>;
    }
}

export default withStyles(styles)(EntryThumbnail);