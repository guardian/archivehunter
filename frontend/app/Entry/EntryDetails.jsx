import React from 'react';
import PropTypes from 'prop-types';
import EntryPreview from './EntryPreview.jsx';
import EntryThumbnail from './EntryThumbnail.jsx';
import FileSizeView from './FileSizeView.jsx';
import EntryJobs from "./EntryJobs.jsx";
import axios from 'axios';
import EntryLightboxBanner from "./EntryLightboxBanner.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

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
            jobsAutorefresh: false,
            lightboxSaving: false
        };

        this.jobsAutorefreshUpdated = this.jobsAutorefreshUpdated.bind(this);
        this.proxyGenerationWasTriggered = this.proxyGenerationWasTriggered.bind(this);
        this.putToLightbox = this.putToLightbox.bind(this);
        this.removeFromLightbox = this.removeFromLightbox.bind(this);
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/loginStatus")
            .then(response=> {
                this.setState({userLogin: response.data})
            }).catch(err=>{
                console.error(err);
                this.setState({lastError: err})
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

    componentDidUpdate(oldProps,oldState){
        //if the highlighted media changes, then disable auto-refresh
        if(oldProps.entry !== this.props.entry && this.state.jobsAutorefresh) this.setState({jobsAutorefresh: false});
    }

    putToLightbox(){
        this.setState({lightboxSaving: true}, ()=>axios.put("/api/lightbox/my/" + this.props.entry.id).then(response=>{
            this.setState({lightboxSaving: false}, ()=>{
                if(this.props.lightboxedCb) this.props.lightboxedCb(this.props.entry.id)
            });
        }).catch(err=>{
            console.error(err);
        }));
    }

    removeFromLightbox(){
        this.setState({lightboxSaving: true}, ()=>axios.delete("/api/lightbox/my/" + this.props.entry.id).then(response=>{
            this.setState({lightboxSaving: false}, ()=>{
                if(this.props.lightboxedCb) this.props.lightboxedCb(this.props.entry.id)
            })
        }).catch(err=>{
            console.error(err);
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

        return <div className="entry-details">
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
                                this.isInLightbox() ? <span>Saved <a onClick={this.removeFromLightbox} style={{cursor: "pointer"}}>remove</a></span> :
                                    <a onClick={this.putToLightbox} style={{cursor: "pointer"}}>Save to lightbox</a>
                            }
                        </td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Name</td>
                        <td className="metadata-entry">{fileinfo.filename}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File path</td>
                        <td className="metadata-entry">{fileinfo.filepath}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">Catalogue</td>
                        <td className="metadata-entry">{this.props.entry.bucket}</td>
                    </tr>
                    <tr>
                        <td className="metadata-heading">File size</td>
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
        </div>
    }
}

export default EntryDetails;