import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import EntryThumbnail from './EntryThumbnail.jsx';
import EntryPreviewSwitcher from './EntryPreviewSwitcher';
import {Button, createStyles, Tooltip, Typography, withStyles} from "@material-ui/core";
import {baseStyles} from "../BaseStyles";
import clsx from "clsx";

const styles = (theme) => Object.assign(createStyles({
    videoPreview: {
        width: "95%",
        marginLeft: "1em",
        marginRight: "1em"
    },
    thumbnailPreview: {
        width: "95%",
        marginLeft: "auto",
        marginRight: "auto",
        display: "block"
    },
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
    }
}), baseStyles);

class EntryPreview extends React.Component {
    static propTypes = {
        entryId: PropTypes.string.isRequired,
        mimeType: PropTypes.object.isRequired,  //MimeType record
        fileExtension: PropTypes.string.isRequired,
        autoPlay: PropTypes.bool,
        hasProxy: PropTypes.bool.isRequired,
        triggeredProxyGeneration: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            previewData: null,
            loading: false,
            didLoad: false,
            lastError: null,
            proxyTypes: [],
            selectedType: "VIDEO",
            processMessage: null
        };

        this.switcherChanged = this.switcherChanged.bind(this);
        this.updateData = this.updateData.bind(this);
        this.initiateCreateProxy = this.initiateCreateProxy.bind(this);
    }

    getPreview(previewType){
        this.setState({lastError: null, previewData:null, loading: true}, ()=>axios.get("/api/proxy/" + this.props.entryId + "/playable?proxyType=" + this.state.selectedType)
            .then(result=>{
                this.setState({didLoad: true, previewData: result.data, loading:false, lastError: null});
            }).catch(err=>{
                console.error(err);
                this.setState({didLoad: true, previewData: null, loading: false, lastError: err});
            })
        );
    }

    bestAvailablePreview(proxyTypes){
        if(proxyTypes.includes("VIDEO")) return "VIDEO";
        if(proxyTypes.includes("AUDIO")) return "AUDIO";
        if(proxyTypes.includes("POSTER")) return "POSTER";
        if(proxyTypes.includes("THUMBNAIL")) return "THUMBNAIL";
        return "THUMBNAIL";
    }

    updateData(){
        return new Promise((resolve,reject)=>
            this.setState({lastError: null, proxyDefs: [], loading: true, processMessage: null, didLoad: false}, ()=>axios.get("/api/proxy/" + this.props.entryId)
                .then(result=>{
                    const proxyTypes = result.data.entries.map(entry=>entry.proxyType);
                    this.setState({proxyTypes: proxyTypes, selectedType: this.bestAvailablePreview(proxyTypes), loading: false, didLoad:true}, ()=>resolve());
                }).catch(err=>{
                    console.error(err);
                    this.setState({proxyTypes: [], loading: false, lastError: err, didLoad: true}, ()=>reject());
                }))
        );
    }

    componentDidMount(){
        this.updateData();
    }

    componentDidUpdate(oldProps, oldState){
        if(oldProps.entryId!==this.props.entryId) this.updateData().then(()=>this.getPreview());
    }

    initiateCreateProxy(){
        axios.post("/api/proxy/generate/" + this.props.entryId + "/" + this.state.selectedType.toLowerCase()).then(result=>{
            const msg = result.data.entry==="disabled" ? "Proxy generation disabled for this storage" : "Proxy generation started";
            this.setState({processMessage: msg}, ()=>{
                if(this.props.triggeredProxyGeneration && result.data.entry!=="disabled") this.props.triggeredProxyGeneration();
            })
        }).catch(err=>{
            console.log(err);
            this.setState({processMessage: "Proxy generation failed, see console log"})
        })
    }

    controlBody(){
        if(this.state.loading){
            return <img style={{marginLeft:"auto",marginRight:"auto",width:"200px",display:"block"}} src="/assets/images/Spinner-1s-200px.gif"/>;
        }

        if(!this.state.previewData){
            return <div className={this.props.classes.centered}>
                <EntryThumbnail mimeType={this.props.mimeType} fileExtension={this.props.fileExtension} entryId={this.props.entryId}/>
                {
                    this.state.didLoad ? <Typography className={this.props.classes.centered}>There is no {this.state.selectedType} proxy available for this item.</Typography> : ""
                }
                {
                    this.state.didLoad ? <Button className={this.props.classes.centered}
                                                 style={{marginTop: "0.4em"}}
                                                 variant="outlined"
                                                 onClick={this.initiateCreateProxy}>
                        Create now
                    </Button> : ""
                }
            </div>;
        }

        const mimeTypeMajor = this.state.previewData.mimeType ? this.state.previewData.mimeType.major : "application";

        switch(mimeTypeMajor){
            case "video":
                return <video className={this.props.classes.videoPreview} src={this.state.previewData.uri} controls={true} autoPlay={this.props.autoPlay}/>;
            case "audio":
                return <audio src={this.state.previewData.uri} controls={true} autoPlay={this.props.autoPlay}/>;
            case "image":
                return <img src={this.state.previewData.uri} className={this.props.classes.thumbnailPreview}/>;
            default:
                return <span className={this.props.classes.errorText}>Unrecognised MIME type: {this.state.previewData.mimeType}</span>
        }
    }

    switcherChanged(newType){
        console.log("switcherChanged: " + newType);
        this.setState({selectedType: newType}, ()=>this.getPreview(newType))
    }

    render(){
        const tooltip = this.state.previewData ? "" : "No preview available";

        return <Tooltip title={tooltip}>
            <>
                {this.controlBody()}
                <hr className={this.props.classes.partialDivider}/>
                <EntryPreviewSwitcher availableTypes={this.state.proxyTypes} typeSelected={this.switcherChanged}/>
                {this.state.processMessage ? <Typography className={clsx(this.props.classes.processingText, this.props.classes.centered)}>{this.state.processMessage}</Typography> : ""}
                <hr className={this.props.classes.partialDivider}/>
            </>
        </Tooltip>
    }
}

export default withStyles(styles)(EntryPreview);