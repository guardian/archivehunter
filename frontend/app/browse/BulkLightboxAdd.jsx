import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import axios from 'axios';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BytesFormatter from "../common/BytesFormatter.jsx";
import Dialog from "react-dialog";

class BulkLightboxAdd extends React.Component {
    static propTypes = {
        path: PropTypes.string.isRequired,
        hideDotFiles:PropTypes.bool.isRequired,
        queryString: PropTypes.string,
        collection: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            quotaExceeded: false,
            quotaRequired: -1,
            quotaLevel: -1,
            bulkRecord: null,
            showingDialog: false,
            reasonText: "",
            reasonGivenError: ""
        };

        this.triggerBulkLightboxing = this.triggerBulkLightboxing.bind(this);
        this.handleModalClose = this.handleModalClose.bind(this);
    }

    componentWillMount() {
        this.checkBulkRecord();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        console.log("componentDidUpdate: ", this.props.collection, this.props.path);
        if(prevProps.collection !== this.props.collection || prevProps.path !== this.props.path) this.checkBulkRecord();
    }

    /**
     * query the server to see if we are already saved as a bulk
     */
    checkBulkRecord() {
        this.setState({loading: true},
            ()=>axios.put("/api/lightbox/bulk/query", this.makeSearchJson(), {headers: {"Content-Type": "application/json"}})
                .then(response=>{
                    this.setState({loading: false, bulkRecord: response.data.entry, lastERror: null});
                }).catch(err=>{
                    console.error(err);
                    this.setState({loading: false, lastError:err})
                })
        )
    }

    makeSearchJson(){
        const pathToSearch = this.props.path ?
            this.props.path.endsWith("/") ? this.props.path.slice(0, this.props.path.length - 1) : this.props.path : null;

        return JSON.stringify({
            hideDotFiles: ! this.props.showDotFiles,
            q: this.props.queryString,
            collection: this.props.collection,
            path: pathToSearch,
            reasonGiven: this.state.reasonText
        })
    }

    triggerBulkLightboxing() {
        if(this.state.loading) return;
        if(this.state.reasonText.length<10){
            this.setState({reasonGivenError: this.state.reasonText.length + " characters does not seem like enough"});
            return;
        }

        this.setState({loading: true, lastError:null, showingDialog: false},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.makeSearchJson(),{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({loading:false, bulkRecord: response.data.objectId ? response.data.objectId : null});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    this.setState({
                        loading: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    this.setState({loading: false, lastError: err});
                }
            })
        )
    }

    //return an icon name based on the component state.
    iconForState() {
        if(this.state.loading) return "redo-alt";
        if(this.state.bulkRecord) return "check";
        return "lightbulb";
    }

    handleModalClose(){
        this.setState({showingDialog: false});
    }

    render(){
        return <div className="centered" style={{marginTop: "0.1em", paddingLeft: "0.5em", display: this.props.path ? "inline":"none"}}>
            {
                this.state.quotaExceeded ? <span><p><FontAwesomeIcon icon="lightbulb" className="button-icon"/>Can't add as this would exceed your quota. You would need <BytesFormatter value={this.state.quotaRequired*1048576}/> but only have <BytesFormatter value={this.state.quotaLevel*1048576}/>.</p></span> : ""
            }
            {
                this.state.quotaExceeded || this.state.bulkRecord ? "" : <span>
                    <FontAwesomeIcon icon={this.iconForState()} className={this.state.loading ? "button-icon spin" : "button-icon"}/>
                    <a style={{cursor: "pointer"}} onClick={evt=>this.setState({showingDialog: true})}>Lightbox All</a>
                </span>
            }
            {
                this.state.bulkRecord ? <span>
                    <FontAwesomeIcon icon={this.iconForState()} className={this.state.loading ? "button-icon spin" : "button-icon"}/>
                    <a>Saved to lightbox</a>
                </span> : ""
            }
            {
                this.state.lastError ? <span className="error-text" style={{marginLeft: "0.6em"}}>Server error, not all items were added</span> : ""
            }
            {this.state.showingDialog && <Dialog modal={true}
                                              title="Bulk Restore"
                                              onClose={this.handleModalClose}
                                              closeOnEscape={true}
                                              hasCloseIcon={true}
                                              isDraggable={true}
                                              position={{x: window.innerWidth / 2 - 250, y: window.innerHeight}}
                                                 height={600}
                                              buttons={
                                                  [{
                                                      text: "Close",
                                                      onClick: () => this.handleModalClose()
                                                  }]
                                              }
                                        >
                <div className="dialog-content" style={{height:"100%", overflowX: "hidden"}}>
                    <p>This action will attempt to restore a large amount of media for download, which will incur costs.</p>
                    <p>Please enter the reason that you need the media, including the project(s) that you are working on and the name of the commissioning editor.</p>
                    <p>If the amount of media is greater than your quota, administrative approval will be required and this information will be used by the administrator to decide whether to allow the restore to proceed</p>
                    <textarea onChange={evt=>this.setState({reasonText: evt.target.value})} value={this.state.reasonText} style={{width: "85%", height:"200px"}}/>
                    <p className="error-text">{this.state.reasonGivenError}</p>
                    <span style={{float: "right"}}><button onClick={this.handleModalClose} style={{marginRight: "2em"}}>Cancel</button><button onClick={this.triggerBulkLightboxing}>Continue</button></span>
                </div>
            </Dialog>
            }
        </div>
    }
}

export default BulkLightboxAdd;