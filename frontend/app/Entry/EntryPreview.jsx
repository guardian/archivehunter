import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import EntryThumbnail from './EntryThumbnail.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import ReactTooltip from 'react-tooltip';
import EntryPreviewSwitcher from './EntryPreviewSwitcher.jsx';

class EntryPreview extends React.Component {
    static propTypes = {
        entryId: PropTypes.string.isRequired,
        mimeType: PropTypes.string.isRequired,
        fileExtension: PropTypes.string.isRequired,
        autoPlay: PropTypes.boolean,
        hasProxy: PropTypes.bool.isRequired
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
        console.log("bestAvailablePreview", proxyTypes);

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

    componentWillMount(){
        console.log(this);
        this.updateData();
    }

    componentDidUpdate(oldProps, oldState){
        if(oldProps.entryId!==this.props.entryId) this.updateData().then(()=>this.getPreview());
    }

    initiateCreateProxy(){
        axios.post("/api/proxy/generate/" + this.props.entryId + "/" + this.state.selectedType.toLowerCase()).then(result=>{
            this.setState({processMessage: "Proxy generation started"})
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
            return <div>
                <EntryThumbnail mimeType={this.props.mimeType} fileExtension={this.props.fileExtension} entryId={this.props.entryId}/>
                {
                    this.state.didLoad ? <p className="centered">There is no {this.state.selectedType} proxy available for this item.</p> : ""
                }
                {
                    this.state.didLoad ? <p className="centered"><a onClick={this.initiateCreateProxy} href="#">Try to create</a> one now?</p> : ""
                }
            </div>;
        }

        switch(this.state.previewData.mimeType.major){
            case "video":
                return <video className="video-preview" src={this.state.previewData.uri} controls={true} autoPlay={this.props.autoPlay}/>;
            case "audio":
                return <audio src={this.state.previewData.uri} controls={true} autoPlay={this.props.autoPlay}/>;
            case "image":
                return <img src={this.state.previewData.uri} className="thumbnail-preview"/>;
            default:
                return <span className="error-text">Unrecognised MIME type: {this.state.previewData.mimeType}</span>
        }
    }

    switcherChanged(newType){
        console.log("switcherChanged: " + newType);
        this.setState({selectedType: newType}, ()=>this.getPreview(newType))
    }

    render(){
        //if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        const tooltip = this.state.previewData ? "" : "No preview available";

        return <span data-tip={tooltip}>
            {this.controlBody()}
            <ReactTooltip/>
            <hr className="partial-divider"/>
            <EntryPreviewSwitcher availableTypes={this.state.proxyTypes} typeSelected={this.switcherChanged}/>
            {this.state.processMessage ? <p className="information">{this.state.processMessage}</p> : ""}
            <hr className="partial-divider"/>
        </span>
    }
}

export default EntryPreview;