import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';
import FileSizeView from './FileSizeView.jsx';
import EntryJobs from "./EntryJobs.jsx";
import axios from 'axios';
import EntryLightboxBanner from "./EntryLightboxBanner.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import MediaDurationComponent from "../common/MediaDurationComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import {handle419} from "../common/Handle419.jsx";

class EntryDetails extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        autoPlay: PropTypes.boolean,
        showJobs: PropTypes.boolean,
        loadJobs: PropTypes.boolean,
        lightboxedCb: PropTypes.func,
        userLogin: null,
        lastError: null,

        preLightboxInsert: PropTypes.object,
        postLightboxInsert: PropTypes.object,
        postJobsInsert: PropTypes.object,
        tableRowsInsert: PropTypes.object
    };

    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError: null,
            jobsAutorefresh: false,
            lightboxSaving: false,

            firstVideoStream: null,
            firstAudioStream: null,
            videoStreamCount: null,
            audioStreamCount: null
        };

        this.jobsAutorefreshUpdated = this.jobsAutorefreshUpdated.bind(this);
        this.proxyGenerationWasTriggered = this.proxyGenerationWasTriggered.bind(this);
        this.putToLightbox = this.putToLightbox.bind(this);
        this.removeFromLightbox = this.removeFromLightbox.bind(this);
        this.triggerAnalyse = this.triggerAnalyse.bind(this);
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/loginStatus")
            .then(response=> {
                this.setState({userLogin: response.data})
            }).catch(err=>{
                handle419(err).then(didRefresh=>{
                    if(didRefresh){
                        this.componentWillMount();
                    } else {
                        console.error(err);
                        this.setState({lastError: err})
                    }
                });
            }));
    }

    extractFileInfo(fullpath){
        const parts = fullpath.split("/");
        const len = parts.length;
        if(len===0){
            return {
                filename: parts[0],
                filepath: ""
            }
        }

        return {
            filename: parts[len-1],
            filepath: parts.slice(0,len-1).join("/")
        }
    }

    jobsAutorefreshUpdated(newValue){
        this.setState({jobsAutorefresh: newValue});
    }

    proxyGenerationWasTriggered(){
        console.log("new proxy generation was started");
        this.setState({jobsAutorefresh: true});
    }

    triggerAnalyse(){
        this.setState({loading: true}, ()=>axios.post("/api/proxy/analyse/" + this.props.entry.id)
            .then(response=>{
                console.log("Media analyse started");
                this.setState({loading: false, jobsAutorefresh: true});
            }).catch(err=>{
                handle419(err).then(didRefresh=>{
                    if(didRefresh){
                        this.triggerAnalyse();
                    } else {
                        console.error(err);
                        this.setState({loading: false, lastError: err});
                    }
                });
            }));
    }
    /**
     * refreshes the component's shortcuts to any media stream data held within the entry metadata.
     * returns a Promise which resolves once all updates are complete.
     */
    refreshStreamsState(){
        return new Promise((resolve,reject)=>{
            if(!this.props.entry.mediaMetadata){
                this.setState({
                    firstVideoStream: null,
                    firstAudioStream: null,
                    videoStreamCount: null,
                    audioStreamCount: null
                }, ()=>resolve())
            } else {
                const vStreams = this.props.entry.mediaMetadata.streams.filter(entry=>entry.codec_type==="video");
                const aStreams = this.props.entry.mediaMetadata.streams.filter(entry=>entry.codec_type==="audio");
                this.setState({
                    firstVideoStream: vStreams.length>0 ? vStreams[0] : null,
                    firstAudioStream: aStreams.length>0 ? aStreams[0] : null,
                    videoStreamCount: vStreams.length,
                    audioStreamCount: aStreams.length
                }, ()=>resolve())
            }
        })
    }

    componentDidUpdate(oldProps,oldState){
        new Promise((resolve,reject)=> {
            //if the highlighted media changes, refresh our knowledge of the media streams
            if (oldProps.entry !== this.props.entry){
                this.refreshStreamsState().then(()=>resolve());
            } else {
                resolve()
            }
        }).then(()=> {
            //if the highlighted media changes, then disable auto-refresh
            if (oldProps.entry !== this.props.entry && this.state.jobsAutorefresh) this.setState({jobsAutorefresh: false});
        });

    }

    putToLightbox(){
        this.setState({lightboxSaving: true}, ()=>axios.put("/api/lightbox/my/" + this.props.entry.id).then(response=>{
            if(this.props.lightboxedCb){
                this.props.lightboxedCb(this.props.entry.id).then(()=>this.setState({lightboxSaving: false}))
            } else {
                console.log("No lightboxCb");
                this.setState({lightboxSaving: false});
            }
        }).catch(err=>{
            console.error(err);
            handle419(err).then(didRefresh=>{
                if(didRefresh){
                    this.putToLightbox();
                }
            })
        }));
    }

    removeFromLightbox(){
        this.setState({lightboxSaving: true}, ()=>axios.delete("/api/lightbox/my/" + this.props.entry.id).then(response=>{
            this.setState({lightboxSaving: false}, ()=>{
                if(this.props.lightboxedCb){
                    this.props.lightboxedCb(this.props.entry.id).then(()=>this.setState({lightboxSaving: false}))
                }  else {
                    console.log("No lightboxCb");
                    this.setState({lightboxSaving: false});
                }
            })
        }).catch(err=>{
            console.error(err);
            handle419(err).then(didRefresh=>{
                if(didRefresh) this.removeFromLightbox();
            })
        }))
    }

    isInLightbox(){
        const matchingEntries = this.props.entry.lightboxEntries.filter(lbEntry=>lbEntry.owner===this.state.userLogin.email);
        return matchingEntries.length>0;
    }

    render(){
        if(!this.props.entry){
            return <div className="entry-details">
            </div>
        }
        const fileinfo = this.extractFileInfo(this.props.entry.path);

        return <div className="entry-details" style={{overflowX: "scroll"}}>
                <EntryPreview entryId={this.props.entry.id}
                              hasProxy={this.props.entry.proxied}
                              fileExtension={this.props.entry.file_extension}
                              mimeType={this.props.entry.mimeType}
                              autoPlay={this.props.autoPlay}
                              triggeredProxyGeneration={this.proxyGenerationWasTriggered}
                />
            <div className="entry-details-insert">{ this.props.preLightboxInsert ? this.props.preLightboxInsert : "" }</div>
            <div className="entry-details-lightboxes" style={{display: this.props.entry.lightboxEntries.length>0 ? "block":"none"}}>
                <span style={{display:"block", marginBottom: "0.4em"}}><FontAwesomeIcon icon="lightbulb" style={{paddingRight: "0.4em"}}/>Lightboxes</span>
                <EntryLightboxBanner lightboxEntries={this.props.entry.lightboxEntries} entryClassName="entry-lightbox-banner-entry-large"/>
            </div>
            <div className="entry-details-insert">{ this.props.postLightboxInsert ? this.props.postLightboxInsert : "" }</div>
            {
                this.props.showJobs ? <EntryJobs entryId={this.props.entry.id}
                                                 loadImmediate={this.props.loadJobs}
                                                 autoRefresh={this.state.jobsAutorefresh}
                                                 autoRefreshUpdated={this.jobsAutorefreshUpdated}/> : ""
            }
            <div className="entry-details-insert">{ this.props.postJobsInsert ? this.props.postJobsInsert : "" }</div>
                <table className="metadata-table">
                    <tbody>
                    <tr>
                        <td className="metadata-heading">Lightbox</td>
                        <td className="metadata-entry">
                            {
                                this.isInLightbox() ?
                                    <span>Saved <a onClick={this.removeFromLightbox} style={{cursor: "pointer"}}>remove</a></span> :
                                    <a onClick={this.putToLightbox} style={{cursor: "pointer"}}>Save to lightbox</a>
                            }
                            <img src="/assets/images/Spinner-1s-44px.svg" style={{display: this.state.lightboxSaving ? "inline" : "none"}}/>
                        </td>
                    </tr>
                    <tr>
                        <td className="metadata-heading spacebelow">Name</td>
                        <td className="metadata-entry">{fileinfo.filename}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading spacebelow">Catalogue</td>
                        <td className="metadata-entry">{this.props.entry.bucket}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Channels</td>
                        <td className="metadata-entry">{
                            this.state.videoStreamCount && this.state.audioStreamCount ? <span>{this.state.videoStreamCount} video,  {this.state.videoStreamCount} audio</span> :
                                <p className="information dont-expand">no data</p>
                        }</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Format</td>
                        <td className="metadata-entry">{
                            this.state.firstVideoStream && this.state.firstAudioStream ? <span>{this.state.firstVideoStream.codec_name} / {this.state.firstAudioStream.codec_name}</span> :
                                <p className="information dont-expand">no data</p>
                        }</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Resolution</td>
                        <td className="metadata-entry">{
                            this.state.firstVideoStream ? <span>{this.state.firstVideoStream.width} x {this.state.firstVideoStream.height}</span> :
                                <p className="information dont-expand">no data</p>
                        }</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Duration</td>
                        <td className="metadata-entry">{
                            this.props.entry.mediaMetadata && this.props.entry.mediaMetadata.format ?
                                <MediaDurationComponent value={this.props.entry.mediaMetadata.format.duration}/> :
                                <p className="information dont-expand">no data</p>
                        }</td>
                    </tr>

                    <tr>
                        <td className="metadata-heading spacebelow">Audio</td>
                        <td className="metadata-entry">{
                            this.state.firstAudioStream ? this.state.firstAudioStream.channel_layout :
                                <p className="information dont-expand">no data</p>
                        }</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File path</td>
                        <td className="metadata-entry">{fileinfo.filepath==="" ? <i>root</i> : fileinfo.filepath}</td>
                    </tr>

                    <tr>
                        <td className="metadata-heading spacebelow">File size</td>
                        <td className="metadata-entry"><FileSizeView rawSize={this.props.entry.size}/></td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Data type</td>
                        <td className="metadata-entry">{this.props.entry.mimeType.major}/{this.props.entry.mimeType.minor}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Storage class</td>
                        <td className="metadata-entry">{this.props.entry.storageClass}</td>
                    </tr>
                    { this.props.tableRowsInsert ? this.props.tableRowsInsert : "" }
                    </tbody>
                </table>
            <p className="information">
                <a onClick={this.triggerAnalyse} style={{cursor: "pointer"}}>Refresh metadata</a>
            </p>
            {
                this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : <span/>
            }
        </div>
    }
}

export default EntryDetails;