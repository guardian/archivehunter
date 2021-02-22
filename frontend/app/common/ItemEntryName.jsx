import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import ErrorViewComponent from './ErrorViewComponent.jsx';
import LoadingThrobber from "./LoadingThrobber.jsx";

class ItemEntryName extends React.Component {
    static propTypes = {
        entryId: PropTypes.string.isRequired,
        showLink: PropTypes.bool,
        id: PropTypes.string  //CSS ID of to assign
    };

    constructor(props){
        super(props);

        this.state = {
            entryName: "",
            loading: false,
            lastError: null
        }
    }

    componentWillMount(){
        this.updateData();
    }

    componentDidUpdate(oldProps, oldState){
        if(this.props.entryId!==oldProps.entryId) this.updateData();
    }

    updateData(){
        if(this.props.entryId) {
            this.setState({
                loading: true,
                lastError: null
            }, () => axios.get("/api/entry/" + this.props.entryId).then(response => {
                this.setState({loading: false, entryName: response.data.entry.path.split("/").pop()})
            }).catch(err => {
                console.error(err);
                this.setState({loading: false, lastError: err})
            }))
        }
    }
    
    render(){
        if(this.state.lastError){
            return <ErrorViewComponent error={this.state.lastError}/>
        } else if(this.state.loading){
            return <LoadingThrobber show={true} small={true}/>
        } else {
            return <p className="inline" id={this.props.id}>{this.state.entryName}&nbsp;<a href={"/browse?open=" + this.props.entryId} style={{display: this.props.showLink ? "inline" : "none"}}>view &gt;</a></p>
        }
    }
}

export default ItemEntryName;
