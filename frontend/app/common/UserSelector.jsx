import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';

class UserSelector extends React.Component {
    static propTypes = {
        onChange: PropTypes.func.isRequired,
        selectedUser: PropTypes.string
    };

    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            userList: []
        }
    }

    componentWillMount() {
        this.setState({loading: true, lastError:null}, ()=>axios.get("/api/user").then(response=>{
            this.setState({loading: false, userList: response.data.entries.map(entry=>entry.userEmail)})
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    render() {
        return <select className="user-selector" onChange={this.props.onChange} value={this.props.selectedUser}>
            {
                this.state.userList.map(entry=><option key={entry} value={entry}>{entry}</option>)
            }
        </select>
    }
}

export default UserSelector;